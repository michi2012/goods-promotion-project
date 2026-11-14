package promotion.serverC.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverC.dto.CancelFailureResponse;
import promotion.serverC.dto.PaymentCancelledMessage;
import promotion.serverC.dto.PaymentResponse;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;
import promotion.serverC.exception.CancelFailureNotFoundException;
import promotion.serverC.exception.InvalidCancelFailureStatusException;
import promotion.serverC.outbox.OutboxEventService;
import promotion.serverC.repository.PaymentCancelFailureRepository;
import promotion.serverC.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PG 결제 취소(사용자 환불·Saga 망취소) 처리. 동작은 docs/specs/user-refund.md "장애 시 동작"을 따른다.
 * PG 호출은 트랜잭션 밖에서 하고, 취소 확정(상태 변경 + Outbox)만 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelService {

    static final String CANCELLED_TOPIC = "payment-cancelled";
    private static final String PAID = "PAID";
    private static final String CANCELLED = "CANCELLED";

    private final PgClient pgClient;
    private final PaymentRepository paymentRepository;
    private final PaymentCancelFailureRepository failureRepository;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    // 자동 재시도 간격·최대 횟수 (docs/specs/user-refund.md 결정 기록)
    @Value("${payment.cancel-retry.interval-minutes:10}")
    private long retryIntervalMinutes;

    @Value("${payment.cancel-retry.max-attempts:6}")
    private int maxAttempts;

    // 선점 후 이 시간 동안 다른 인스턴스가 같은 행을 집지 못한다 (재시도 1건이 끝나기에 충분한 시간)
    @Value("${payment.cancel-retry.lease-minutes:5}")
    private long leaseMinutes;

    @Value("${payment.cancel-retry.batch-size:50}")
    private int batchSize;

    private Counter needsAdmin;

    @PostConstruct
    void initMetrics() {
        needsAdmin = Counter.builder("business_payment_cancel_retry_exhausted_total")
                .description("PG 취소를 자동으로 끝내지 못해 관리자 처리가 필요해진 건수 (재시도 소진·취소할 수 없는 결제 상태)")
                .register(meterRegistry);
    }

    /**
     * payment-cancel 한 건을 처리한다. PG 취소 실패는 예외로 던진다 (호출자가 재시도·실패 기록을 맡는다).
     */
    public void cancel(String orderId) {
        PaymentResponse payment = paymentRepository.findByOrderId(orderId).orElse(null);
        String status = payment == null ? null : payment.status();

        if (CANCELLED.equals(status)) {
            // 이미 취소됨 — PG를 다시 부르지 않고 확정 알림만 다시 보낸다 (serverA가 놓쳤을 수 있다)
            log.info("[PaymentCancel] 이미 취소된 결제, payment-cancelled 재발행: orderId={}", orderId);
            completeCancel(orderId);
            return;
        }
        if (!PAID.equals(status)) {
            recordNeedsAdmin(orderId, "취소할 수 없는 결제 상태: " + status);
            return;
        }

        PgCancelResult result = pgClient.cancelPayment(orderId);
        log.info("[PaymentCancel] PG 취소 완료: orderId={}, result={}", orderId, result);
        completeCancel(orderId);
    }

    /**
     * Kafka 재시도를 다 쓰고 DLT에 도착한 취소를 자동 재시도 대기로 기록한다.
     */
    public void recordDltArrival(String orderId, String error) {
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(retryIntervalMinutes);
        transactionTemplate.executeWithoutResult(tx ->
                failureRepository.findByOrderId(orderId).ifPresentOrElse(
                        f -> f.recordDltArrival(error, nextRetryAt),
                        () -> failureRepository.save(PaymentCancelFailure.retrying(orderId, error, nextRetryAt))));
        log.warn("[PaymentCancel] 자동 재시도 대기로 기록: orderId={}, nextRetryAt={}", orderId, nextRetryAt);
    }

    /**
     * 재시도 시각이 지난 RETRYING 행을 선점해 한 번씩 재시도한다. 처리(선점 성공)한 건수를 돌려준다.
     */
    public int retryDue(LocalDateTime now) {
        List<PaymentCancelFailure> due = failureRepository.findDueForRetry(now, PageRequest.of(0, batchSize));
        int processed = 0;
        for (PaymentCancelFailure failure : due) {
            Integer claimed = transactionTemplate.execute(tx ->
                    failureRepository.claimForRetry(failure.getId(), now, now.plusMinutes(leaseMinutes)));
            if (claimed == null || claimed == 0) {
                continue; // 다른 인스턴스가 먼저 집음
            }
            retryOnce(failure.getOrderId(), now);
            processed++;
        }
        return processed;
    }

    /**
     * 관리자 조회용 실패 목록. 처리 대상(RETRYING·EXHAUSTED)만 허용한다.
     */
    public List<CancelFailureResponse> listFailures(String status) {
        CancelFailureStatus parsed = parseListableStatus(status);
        return failureRepository.findByStatusOrderByIdDesc(parsed).stream()
                .map(CancelFailureResponse::from)
                .toList();
    }

    /**
     * 관리자 재처리: PG 취소를 즉시 한 번 실행한다. 이미 RESOLVED면 아무것도 하지 않고 현재 상태를 돌려준다.
     */
    public CancelFailureResponse retryManually(String orderId) {
        PaymentCancelFailure failure = failureRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CancelFailureNotFoundException(orderId));
        if (failure.getStatus() != CancelFailureStatus.RESOLVED) {
            try {
                cancel(orderId);
                log.info("[PaymentCancel] 관리자 재처리 성공: orderId={}", orderId);
            } catch (Exception e) {
                log.warn("[PaymentCancel] 관리자 재처리 실패: orderId={}, error={}", orderId, e.getMessage());
                transactionTemplate.executeWithoutResult(tx ->
                        failureRepository.findByOrderId(orderId)
                                .ifPresent(f -> f.recordManualRetryFailure(e.getMessage())));
            }
        }
        return failureRepository.findByOrderId(orderId)
                .map(CancelFailureResponse::from)
                .orElseThrow(() -> new CancelFailureNotFoundException(orderId));
    }

    private CancelFailureStatus parseListableStatus(String status) {
        if (status == null) {
            return CancelFailureStatus.EXHAUSTED;
        }
        try {
            CancelFailureStatus parsed = CancelFailureStatus.valueOf(status);
            if (parsed != CancelFailureStatus.RESOLVED) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 400으로 응답
        }
        throw new InvalidCancelFailureStatusException(status);
    }

    private void retryOnce(String orderId, LocalDateTime now) {
        try {
            cancel(orderId); // 성공 시 completeCancel이 RESOLVED로, 취소할 수 없는 상태면 EXHAUSTED로 바꾼다
        } catch (Exception e) {
            boolean exhausted = Boolean.TRUE.equals(transactionTemplate.execute(tx ->
                    failureRepository.findByOrderId(orderId)
                            .map(f -> f.recordAutoRetryFailure(e.getMessage(), maxAttempts,
                                    now.plusMinutes(retryIntervalMinutes)))
                            .orElse(false)));
            if (exhausted) {
                log.error("🚨 [PaymentCancel] 자동 재시도 {}회 소진 — 관리자 처리 필요: orderId={}, error={}",
                        maxAttempts, orderId, e.getMessage());
                needsAdmin.increment();
            } else {
                log.warn("[PaymentCancel] 자동 재시도 실패: orderId={}, error={}", orderId, e.getMessage());
            }
        }
    }

    private void completeCancel(String orderId) {
        transactionTemplate.executeWithoutResult(tx -> {
            paymentRepository.updateStatusIfCurrent(orderId, PAID, CANCELLED);
            outboxEventService.save(orderId, CANCELLED_TOPIC, new PaymentCancelledMessage(orderId, LocalDateTime.now()));
            failureRepository.findByOrderId(orderId)
                    .filter(f -> f.getStatus() != CancelFailureStatus.RESOLVED)
                    .ifPresent(PaymentCancelFailure::markResolved);
        });
    }

    // 재시도해도 소용없는 경우 — PG를 부르지 않고 관리자 확인 대상으로 남긴다
    private void recordNeedsAdmin(String orderId, String reason) {
        log.error("🚨 [PaymentCancel] {} — 관리자 확인 필요: orderId={}", reason, orderId);
        transactionTemplate.executeWithoutResult(tx ->
                failureRepository.findByOrderId(orderId).ifPresentOrElse(
                        f -> f.markExhausted(reason),
                        () -> failureRepository.save(PaymentCancelFailure.exhausted(orderId, reason))));
        needsAdmin.increment();
    }
}
