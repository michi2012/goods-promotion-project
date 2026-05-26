package weverse.serverC.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.exception.PgPaymentException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockPgClient implements PgClient {

    private final MeterRegistry meterRegistry;

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
    @CircuitBreaker(name = "pgClientCb", fallbackMethod = "fallbackProcessPayments")
    public List<String> processPayments(List<PurchaseMessage> messages) {
        log.info("[MockPgClient] PG 결제 요청 처리 시작: 대상 건수 = {}", messages.size());
        List<String> failedTraceIds = new ArrayList<>();

        try { Thread.sleep(500);
        } catch (InterruptedException e) {
        }

        for (PurchaseMessage msg : messages) {
            if (Math.random() < 0.01) {
                log.warn("🚨 [MockPgClient] 결제 실패 시뮬레이션 당첨!: orderId={}", msg.orderId());
                failedTraceIds.add(msg.orderId());
            } else {
                log.info("✅ [MockPgClient] 결제 승인 성공: orderId={}", msg.orderId());
            }
        }
        return failedTraceIds;
    }

    // 차단(Open) 상태일 때 실행될 Fallback 로직
    // PgPaymentException을 throw해 PaymentService가 pg_system_error로 분류하도록 시그널을 전달한다.
    public List<String> fallbackProcessPayments(List<PurchaseMessage> messages, Throwable t) {
        log.error("🚨 [Circuit Breaker OPEN] PG사 장애 감지. 사유: {}", t.getMessage());
        throw new PgPaymentException("PG 시스템 장애: " + t.getMessage());
    }

    @Override
    public void cancelPayments(List<String> orderIds) {
        if (orderIds.isEmpty()) return;
        log.warn("🚨 PG사로 결제 강제 취소(망취소)를 요청했습니다. 대상: {}건", orderIds.size());
        refundRequests.increment(orderIds.size());
        refundDuration.record(() -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
}