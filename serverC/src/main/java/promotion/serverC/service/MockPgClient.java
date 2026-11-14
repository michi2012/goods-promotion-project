package promotion.serverC.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import promotion.serverC.dto.PurchaseMessage;
import promotion.serverC.exception.PgAmbiguousResponseException;
import promotion.serverC.exception.PgPaymentException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockPgClient implements PgClient {

    private final MeterRegistry meterRegistry;

    // 멱등 키(orderId) 재사용 — 한 번 확정된 결과를 영구히 기억해 재시도 시 중복 처리를 막는다.
    // 결제 결과는 재고 품절 캐시(Caffeine)처럼 만료시킬 이유가 없어 TTL 없는 맵으로 충분하다.
    // 승인 금액도 함께 기억해, 조회(queryTransaction) 시 실제 PG처럼 승인 금액을 돌려준다.
    private final Map<String, PgTransactionResult> transactionStore = new ConcurrentHashMap<>();

    // 취소한 거래의 멱등 키(orderId). 승인 결과와 같은 이유로 TTL 없이 기억한다.
    private final Set<String> cancelledOrders = ConcurrentHashMap.newKeySet();

    private static final double AMBIGUOUS_RESPONSE_RATE = 0.01;
    private static final double BUSINESS_REJECTION_RATE = 0.01;

    private Counter refundRequests;
    private Timer refundDuration;

    @PostConstruct
    void initMetrics() {
        refundRequests = Counter.builder("business_payment_pg_refund_request_total")
                .description("PG사 결제 취소(환불) 요청 총 건수")
                .register(meterRegistry);
        refundDuration = Timer.builder("business_refund_duration_seconds")
                .description("PG사 환불 처리 소요 시간")
                .register(meterRegistry);
    }

    @Override
    @CircuitBreaker(name = "pgClientCb", fallbackMethod = "fallbackProcessPayment")
    public PgTransactionResult processPayment(PurchaseMessage message) {
        String orderId = message.orderId();

        // 멱등 키(orderId)로 이미 확정된 결과가 있으면 재실행 없이 그대로 반환한다.
        PgTransactionResult cached = transactionStore.get(orderId);
        if (cached != null) {
            log.info("[MockPgClient] 멱등 키 재사용 감지, 캐시된 결과 반환: orderId={}, status={}", orderId, cached.status());
            return cached;
        }

        // 모의 PG는 요청 금액 그대로 승인한다. 금액 불일치 시나리오는 PaymentService 단위 테스트에서 검증한다.
        PgTransactionResult approved = PgTransactionResult.approved(message.totalAmount());

        log.debug("[MockPgClient] PG 결제 요청 처리 시작: orderId={}", orderId);
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (Math.random() < AMBIGUOUS_RESPONSE_RATE) {
            // 응답 전달이 안 된 상황(타임아웃성) — 내부적으로는 처리가 됐을 수도, 안 됐을 수도 있다.
            // 결과를 알 수 없다는 걸 그대로 흉내 내기 위해 처리 완료 여부 자체를 50:50으로 결정한다.
            if (Math.random() < 0.5) {
                transactionStore.put(orderId, approved);
                log.warn("🚨 [MockPgClient] 애매한 응답(내부적으로는 처리됨): orderId={}", orderId);
            } else {
                log.warn("🚨 [MockPgClient] 애매한 응답(내부적으로도 미처리): orderId={}", orderId);
            }
            throw new PgAmbiguousResponseException("PG 응답을 받지 못함: orderId=" + orderId);
        }

        boolean success = Math.random() >= BUSINESS_REJECTION_RATE;
        PgTransactionResult result = success ? approved : PgTransactionResult.rejected();
        transactionStore.put(orderId, result);

        if (!success) {
            log.warn("🚨 [MockPgClient] 결제 실패: orderId={}", orderId);
            return result;
        }
        log.debug("✅ [MockPgClient] 결제 승인 성공: orderId={}, approvedAmount={}", orderId, result.approvedAmount());
        return result;
    }

    @Override
    public PgTransactionResult queryTransaction(String orderId) {
        return transactionStore.getOrDefault(orderId, PgTransactionResult.notFound());
    }

    // 차단(Open) 상태일 때 실행될 Fallback 로직
    // PgPaymentException을 throw해 PaymentService가 pg_system_error로 분류하도록 시그널을 전달한다.
    public PgTransactionResult fallbackProcessPayment(PurchaseMessage message, Throwable t) {
        log.error("🚨 [Circuit Breaker OPEN] PG사 장애 감지. 사유: {}", t.getMessage());
        throw new PgPaymentException("PG 시스템 장애: " + t.getMessage());
    }

    @Override
    public PgCancelResult cancelPayment(String orderId) {
        // 멱등 키(orderId): 이미 취소한 거래는 다시 취소하지 않고 "이미 취소됨"을 돌려준다 (이중 환불 방지).
        if (!cancelledOrders.add(orderId)) {
            log.info("[MockPgClient] 이미 취소된 거래: orderId={}", orderId);
            return PgCancelResult.ALREADY_CANCELLED;
        }
        log.warn("🚨 PG사로 결제 취소를 요청했습니다: orderId={}", orderId);
        refundRequests.increment();
        refundDuration.record(() -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        return PgCancelResult.CANCELLED;
    }
}