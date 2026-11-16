package promotion.serverA.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisStockService {

    private final StringRedisTemplate redisTemplate;

    private final Cache<Long, Boolean> soldOutCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    // stock >= quantity이면 DECRBY 후 남은 재고 반환, 부족하면 -1, 키 없으면 -2 반환
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then return -2 end
            if stock >= tonumber(ARGV[1]) then
                return redis.call('DECRBY', KEYS[1], ARGV[1])
            else
                return -1
            end
            """, Long.class);

    private static final String STOCK_KEY_PREFIX = "goods:stock:";
    private static final String USER_PURCHASE_KEY_PREFIX = "user:purchase:";
    // TTL 1일 — Kafka 재시도 구간(수 초~수십 초)보다 충분히 길다
    private static final String REFUND_RESTORED_KEY_PREFIX = "refund:restored:";

    public void initStock(Long goodsId, int stock) {
        redisTemplate.opsForValue().set(stockKey(goodsId), String.valueOf(stock));
        log.info("[Redis 재고 초기화] goodsId={}, stock={}", goodsId, stock);
    }

    /**
     * @return >= 0: 선점 성공(남은 재고), -1: 재고 부족, -2: Redis 키 없음(미초기화)
     */
    public Long reserveStock(Long goodsId, int quantity) {
        Long result = redisTemplate.execute(RESERVE_SCRIPT, List.of(stockKey(goodsId)), String.valueOf(quantity));
        if (result == null || result == -1L) {
            soldOutCache.put(goodsId, true);
        }
        return result;
    }

    public boolean isKnownSoldOut(Long goodsId) {
        return Boolean.TRUE.equals(soldOutCache.getIfPresent(goodsId));
    }

    // Kafka produce 실패 시 선점한 재고를 복구
    public void releaseStock(Long goodsId, int quantity) {
        redisTemplate.opsForValue().increment(stockKey(goodsId), quantity);
        soldOutCache.invalidate(goodsId);
    }

    public Long getCurrentStock(Long goodsId) {
        String val = redisTemplate.opsForValue().get(stockKey(goodsId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    /**
     * @return true: 최초 구매 시도 (구매 진행 가능) / false: 이미 구매한 유저 (중복)
     */
    public boolean tryMarkUserPurchased(Long userId, Long goodsId) {
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(userPurchaseKey(userId, goodsId), "1", Duration.ofHours(1));
        return Boolean.TRUE.equals(set);
    }

    // Kafka produce 실패 시 중복 방어 플래그 복구
    public void releaseUserPurchase(Long userId, Long goodsId) {
        redisTemplate.delete(userPurchaseKey(userId, goodsId));
    }

    // 환불 완료 시: 구매 표시를 만료 없이 다시 써서 환불한 사용자의 재구매를 영구히 막는다 (명세 P-12).
    // tryMarkUserPurchased의 TTL(1시간)이 지난 뒤 환불돼도 기회가 되살아나지 않게 한다.
    public void markUserPurchasedPermanently(Long userId, Long goodsId) {
        redisTemplate.opsForValue().set(userPurchaseKey(userId, goodsId), "1");
    }

    // 환불 완료의 Redis 재고 복구를 주문당 한 번으로 제한한다 (Kafka 재시도 시 이중 복구 방지 — Saga markFailedAndCheck와 같은 역할).
    // @return true: 이번 호출이 처음 — 재고를 복구한다 / false: 이미 복구했다 (재시도)
    public boolean tryMarkRefundRestored(String orderId) {
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(REFUND_RESTORED_KEY_PREFIX + orderId, "1", Duration.ofDays(1));
        return Boolean.TRUE.equals(set);
    }

    private String stockKey(Long goodsId) {
        return STOCK_KEY_PREFIX + goodsId;
    }

    private String userPurchaseKey(Long userId, Long goodsId) {
        return USER_PURCHASE_KEY_PREFIX + userId + ":" + goodsId;
    }
}
