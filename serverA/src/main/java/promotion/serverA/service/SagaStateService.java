package promotion.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import promotion.serverA.dto.SagaStateData;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaStateService {

    private final StringRedisTemplate redisTemplate;

    private static final String STATE_PREFIX = "saga:state:";

    public void initSagaState(String orderId, Long orderEntityId, Long userId, Long goodsId, int quantity) {
        String stateKey = STATE_PREFIX + orderId;
        redisTemplate.opsForHash().putAll(stateKey, Map.of(
                "orderEntityId",          String.valueOf(orderEntityId),
                "userId",                 String.valueOf(userId),
                "goodsId",                String.valueOf(goodsId),
                "quantity",               String.valueOf(quantity),
                "statusUpdateCompleted",  "false",
                "paymentCompleted",       "false",
                "failed",                 "false",
                "createdAt",              String.valueOf(System.currentTimeMillis())
        ));

        log.info("[SagaState] 초기화 완료: orderId={}", orderId);
    }

    public SagaStateData getSagaState(String orderId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(STATE_PREFIX + orderId);
        if (entries.isEmpty()) return null;

        return new SagaStateData(
                Long.parseLong((String) entries.get("orderEntityId")),
                Long.parseLong((String) entries.get("userId")),
                Long.parseLong((String) entries.get("goodsId")),
                Integer.parseInt((String) entries.get("quantity"))
        );
    }

    // 완료 플래그 설정 후 두 플래그 모두 true면 true 반환
    public boolean markStatusUpdateCompleted(String orderId) {
        redisTemplate.opsForHash().put(STATE_PREFIX + orderId, "statusUpdateCompleted", "true");
        return isBothCompleted(orderId);
    }

    public boolean markPaymentCompleted(String orderId) {
        redisTemplate.opsForHash().put(STATE_PREFIX + orderId, "paymentCompleted", "true");
        return isBothCompleted(orderId);
    }

    // failed 플래그 설정 (멱등성 보장용). 이미 failed면 true 반환
    public boolean markFailedAndCheck(String orderId) {
        Long result = redisTemplate.opsForHash().increment(STATE_PREFIX + orderId, "failedOnce", 1L);
        if (result == null || result > 1) {
            return false; // 이미 처리 중
        }
        redisTemplate.opsForHash().put(STATE_PREFIX + orderId, "failed", "true");
        return true;
    }

    public boolean isFailed(String orderId) {
        Object val = redisTemplate.opsForHash().get(STATE_PREFIX + orderId, "failed");
        return "true".equals(val);
    }

    public boolean isPaymentCompleted(String orderId) {
        Object val = redisTemplate.opsForHash().get(STATE_PREFIX + orderId, "paymentCompleted");
        return "true".equals(val);
    }

    public long getCreatedAt(String orderId) {
        Object val = redisTemplate.opsForHash().get(STATE_PREFIX + orderId, "createdAt");
        if (val == null) return 0L;
        return Long.parseLong((String) val);
    }

    public void deleteSagaState(String orderId) {
        redisTemplate.delete(STATE_PREFIX + orderId);
    }

    private boolean isBothCompleted(String orderId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(STATE_PREFIX + orderId);
        return "true".equals(entries.get("statusUpdateCompleted"))
                && "true".equals(entries.get("paymentCompleted"));
    }
}
