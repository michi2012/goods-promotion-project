package promotion.serverC.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverC.dto.PaymentResponse;
import promotion.serverC.dto.PaymentResultMessage;
import promotion.serverC.dto.PurchaseMessage;
import promotion.serverC.exception.PaymentNotFoundException;
import promotion.serverC.exception.PgAmbiguousResponseException;
import promotion.serverC.exception.PgPaymentException;
import promotion.serverC.outbox.OutboxEventService;
import promotion.serverC.repository.PaymentRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    private static final String RESULT_TOPIC = "payment-result";

    private Counter paymentAttempts;
    private Counter paymentSuccess;
    private Counter paymentErrorPgRejection;
    private Counter paymentErrorPgSystemError;

    @PostConstruct
    void initMetrics() {
        paymentAttempts = Counter.builder("business_payment_attempts_total")
                .description("총 결제 시도 횟수")
                .register(meterRegistry);
        paymentSuccess = Counter.builder("business_payment_success_total")
                .description("총 결제 성공 횟수")
                .register(meterRegistry);
        paymentErrorPgRejection = Counter.builder("business_payment_error_total")
                .description("PG 정상 응답 후 거절 (잔액부족, 카드한도 등)")
                .tag("type", "pg_rejection")
                .register(meterRegistry);
        paymentErrorPgSystemError = Counter.builder("business_payment_error_total")
                .description("PG 시스템 장애 (서킷브레이커 개방, 타임아웃 등)")
                .tag("type", "pg_system_error")
                .register(meterRegistry);
    }

    @Transactional
    public void processPayment(PurchaseMessage msg) {
        paymentAttempts.increment();
        if (!paymentRepository.claimOrder(msg)) {
            log.info("[Payment] 중복 수신 무시: orderId={}", msg.orderId());
            return;
        }

        boolean success;
        try {
            success = callPgWithAmbiguousRetry(msg);
        } catch (PgPaymentException e) {
            // 서킷브레이커 개방, 또는 애매한 응답 재시도까지 실패 — re-throw 금지: 트랜잭션 커밋으로 SAGA 보상 트리거 보장
            paymentErrorPgSystemError.increment();
            paymentRepository.updateOrderStatus(List.of(msg.orderId()), "FAILED");
            outboxEventService.save(msg.orderId(), RESULT_TOPIC,
                    new PaymentResultMessage(msg.orderId(), false, "PG 시스템 장애"));
            return;
        }
        paymentRepository.updateOrderStatus(List.of(msg.orderId()), success ? "PAID" : "FAILED");

        if (success) {
            paymentSuccess.increment();
        } else {
            // PG가 정상 응답했으나 거절 (잔액부족, 카드한도 등 비즈니스 거절)
            paymentErrorPgRejection.increment();
        }

        outboxEventService.save(msg.orderId(), RESULT_TOPIC,
                new PaymentResultMessage(msg.orderId(), success, success ? null : "결제 거절"));
    }

    // 애매한 응답(PgAmbiguousResponseException)을 조회로 재확인한다. 재시도도 애매하면
    // PgPaymentException으로 바꿔 던져 호출자의 기존 "시스템 장애" 처리 경로를 그대로 재사용한다.
    private boolean callPgWithAmbiguousRetry(PurchaseMessage msg) {
        try {
            return pgClient.processPayment(msg);
        } catch (PgAmbiguousResponseException e) {
            return resolveAmbiguousPayment(msg);
        }
    }

    private boolean resolveAmbiguousPayment(PurchaseMessage msg) {
        String orderId = msg.orderId();
        log.warn("[Payment] 애매한 응답 감지, 조회로 확인: orderId={}", orderId);
        PgTransactionStatus status = pgClient.queryTransaction(orderId);

        if (status != PgTransactionStatus.NOT_FOUND) {
            log.info("[Payment] 조회로 상태 확정: orderId={}, status={}", orderId, status);
            return status == PgTransactionStatus.APPROVED;
        }

        log.info("[Payment] 조회 결과 미확인, 1회 재시도: orderId={}", orderId);
        try {
            return pgClient.processPayment(msg);
        } catch (PgAmbiguousResponseException e) {
            throw new PgPaymentException("재시도 후에도 애매한 응답: orderId=" + orderId);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getByUserId(Long userId, int page, int size) {
        return paymentRepository.findByUserId(userId, page, size);
    }
}
