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
                        // 결제 상태를 모르면 판단하지 않는다. EXPIRED로 처리하면 serverC에서 실제로 승인된 결제가
                        // 망취소 없이 남을 수 있다(Saga 상태에 결제완료 플래그가 없으므로). Saga 상태를 그대로 두고 다음 주기에 재조회한다.
                        case UNKNOWN -> {
                            log.warn("[SagaTimeout] 결제 상태 확인 불가, 다음 주기 재시도: orderId={}", orderId);
                            meterRegistry.counter("saga.timeout.payment_unknown").increment();
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
