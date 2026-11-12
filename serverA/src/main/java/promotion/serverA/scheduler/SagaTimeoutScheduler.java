package promotion.serverA.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    // (조회 실패 시에는 안전하게 기존 동작(EXPIRED)으로 폴백)
                    if (paymentQueryClient.checkPaymentStatus(orderId) == PaymentCheckStatus.PAID) {
                        log.warn("[SagaTimeout] 타임아웃이지만 결제 완료 확인, PAID로 보정: orderId={}", orderId);
                        sagaOrchestratorService.reconcileToPaid(orderId);
                    } else {
                        log.warn("[SagaTimeout] 3분 초과 미완료 Saga 탐지: orderId={}", orderId);
                        sagaOrchestratorService.handleSagaFailure(orderId, "TIMEOUT");
                        meterRegistry.counter("saga.expired.total").increment();
                    }
                    expiredCount++;
                }
            }
        }

        if (expiredCount > 0) {
            log.info("[SagaTimeout] EXPIRED 처리 완료: {}건", expiredCount);
        }
    }
}
