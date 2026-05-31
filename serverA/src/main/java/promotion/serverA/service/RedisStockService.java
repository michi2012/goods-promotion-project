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

    private String stockKey(Long goodsId) {
        return STOCK_KEY_PREFIX + goodsId;
    }

    private String userPurchaseKey(Long userId, Long goodsId) {
        return USER_PURCHASE_KEY_PREFIX + userId + ":" + goodsId;
    }
}
