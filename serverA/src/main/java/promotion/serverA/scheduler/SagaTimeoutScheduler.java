package promotion.serverA.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverA.service.SagaOrchestratorService;
import promotion.serverA.service.SagaStateService;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutScheduler {

    private final StringRedisTemplate redisTemplate;
    private final SagaStateService sagaStateService;
    private final SagaOrchestratorService sagaOrchestratorService;

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
                    log.warn("[SagaTimeout] 3분 초과 미완료 Saga 탐지: orderId={}", orderId);
                    sagaOrchestratorService.handleSagaFailure(orderId, "TIMEOUT");
                    expiredCount++;
                }
            }
        }

        if (expiredCount > 0) {
            log.info("[SagaTimeout] EXPIRED 처리 완료: {}건", expiredCount);
        }
    }
}
