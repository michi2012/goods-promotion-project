package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import weverse.serverA.dto.SagaStateData;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaStateService {

    private final StringRedisTemplate redisTemplate;

    private static final String STATE_PREFIX = "saga:state:";
    private static final String HOLD_PREFIX  = "saga:hold:";

    public void initSagaState(String traceId, Long orderId, Long userId, Long goodsId, int quantity) {
        String stateKey = STATE_PREFIX + traceId;
        redisTemplate.opsForHash().putAll(stateKey, Map.of(
                "orderId",                String.valueOf(orderId),
                "userId",                 String.valueOf(userId),
                "goodsId",                String.valueOf(goodsId),
                "quantity",               String.valueOf(quantity),
                "statusUpdateCompleted",  "false",
                "paymentCompleted",       "false",
                "failed",                 "false",
                "createdAt",              String.valueOf(System.currentTimeMillis())
        ));

        // 소프트 홀드: 10분 TTL (스케줄러가 만료 여부 기준으로 활용)
        redisTemplate.opsForValue().set(HOLD_PREFIX + traceId, "HOLDING", Duration.ofMinutes(10));

        log.info("[SagaState] 초기화 완료: traceId={}", traceId);
    }

    public SagaStateData getSagaState(String traceId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(STATE_PREFIX + traceId);
        if (entries.isEmpty()) return null;

        return new SagaStateData(
                Long.parseLong((String) entries.get("orderId")),
                Long.parseLong((String) entries.get("userId")),
                Long.parseLong((String) entries.get("goodsId")),
                Integer.parseInt((String) entries.get("quantity"))
        );
    }

    // 완료 플래그 설정 후 두 플래그 모두 true면 true 반환
    public boolean markStatusUpdateCompleted(String traceId) {
        redisTemplate.opsForHash().put(STATE_PREFIX + traceId, "statusUpdateCompleted", "true");
        return isBothCompleted(traceId);
    }

    public boolean markPaymentCompleted(String traceId) {
        redisTemplate.opsForHash().put(STATE_PREFIX + traceId, "paymentCompleted", "true");
        return isBothCompleted(traceId);
    }

    // failed 플래그 설정 (멱등성 보장용). 이미 failed면 true 반환
    public boolean markFailedAndCheck(String traceId) {
        Long result = redisTemplate.opsForHash().increment(STATE_PREFIX + traceId, "failedOnce", 1L);
        if (result == null || result > 1) {
            return false; // 이미 처리 중
        }
        redisTemplate.opsForHash().put(STATE_PREFIX + traceId, "failed", "true");
        return true;
    }

    public boolean isFailed(String traceId) {
        Object val = redisTemplate.opsForHash().get(STATE_PREFIX + traceId, "failed");
        return "true".equals(val);
    }

    public long getCreatedAt(String traceId) {
        Object val = redisTemplate.opsForHash().get(STATE_PREFIX + traceId, "createdAt");
        if (val == null) return 0L;
        return Long.parseLong((String) val);
    }

    public void deleteSagaState(String traceId) {
        redisTemplate.delete(STATE_PREFIX + traceId);
        redisTemplate.delete(HOLD_PREFIX + traceId);
    }

    private boolean isBothCompleted(String traceId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(STATE_PREFIX + traceId);
        return "true".equals(entries.get("statusUpdateCompleted"))
                && "true".equals(entries.get("paymentCompleted"));
    }
}
