package promotion.serverA.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverA.client.PaymentCheckStatus;
import promotion.serverA.client.PaymentQueryClient;
import promotion.serverA.service.SagaOrchestratorService;
import promotion.serverA.service.SagaStateService;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutScheduler {

    private final StringRedisTemplate redisTemplate;
    private final SagaStateService sagaStateService;
    private final SagaOrchestratorService sagaOrchestratorService;
    private final PaymentQueryClient paymentQueryClient;
    private final MeterRegistry meterRegistry;

    private static final long TIMEOUT_MS = 3 * 60 * 1000L;
    private static final String STATE_PATTERN = "saga:state:*";

    // 결제조회 UNKNOWN(serverC 장애)일 때 Saga 생성 시점부터 기다리는 상한. E2E에서 짧게 주입한다.
    @Value("${saga.timeout.payment-unknown-max-minutes:30}")
    private long paymentUnknownMaxMinutes;

    @Scheduled(fixedDelay = 30_000)
    public void checkTimeouts() {
        log.debug("[SagaTimeout] 타임아웃 스캔 시작");
        long now = System.currentTimeMillis();
        int expiredCount = 0;

        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(STATE_PATTERN).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String orderId = key.replace("saga:state:", "");

                if (sagaStateService.isFailed(orderId)) continue;

                long createdAt = sagaStateService.getCreatedAt(orderId);
                if (createdAt == 0L) continue;

                if ((now - createdAt) >= TIMEOUT_MS) {
                    // EXPIRED 처리 전, 결제가 실제로는 완료됐는지 serverC에 직접 확인한다.
                    switch (paymentQueryClient.checkPaymentStatus(orderId)) {
                        case PAID -> {
                            log.warn("[SagaTimeout] 타임아웃이지만 결제 완료 확인, PAID로 보정: orderId={}", orderId);
                            sagaOrchestratorService.reconcileToPaid(orderId);
                            expiredCount++;
                        }
                        case NOT_PAID -> {
                            log.warn("[SagaTimeout] 3분 초과 미완료 Saga 탐지: orderId={}", orderId);
                            sagaOrchestratorService.handleSagaFailure(orderId, "TIMEOUT");
                            meterRegistry.counter("saga.expired.total").increment();
                            expiredCount++;
                        }
                        // 결제 상태를 모르면 상한 전까지는 판단하지 않고 다음 주기에 재조회한다.
                        // 상한이 지나면 재고 묶임을 끝내기 위해 EXPIRED 처리한다. serverC에서 실제로 승인된 결제는
                        // 늦은 결제 성공 보상 또는 종료 주문 재대사(reconcileTerminatedOrders, 24시간)가 망취소한다.
                        case UNKNOWN -> {
                            meterRegistry.counter("saga.timeout.payment_unknown").increment();
                            if ((now - createdAt) >= paymentUnknownMaxMinutes * 60_000L) {
                                log.error("🚨 [SagaTimeout] 결제 상태 확인 불가 {}분 초과, EXPIRED 처리: orderId={}", paymentUnknownMaxMinutes, orderId);
                                sagaOrchestratorService.handleSagaFailure(orderId, "TIMEOUT");
                                meterRegistry.counter("saga.timeout.payment_unknown_expired").increment();
                                meterRegistry.counter("saga.expired.total").increment();
                                expiredCount++;
                            } else {
                                log.warn("[SagaTimeout] 결제 상태 확인 불가, 다음 주기 재시도: orderId={}", orderId);
                            }
                        }
                    }
                }
            }
        }

        if (expiredCount > 0) {
            log.info("[SagaTimeout] EXPIRED 처리 완료: {}건", expiredCount);
        }
    }
}
