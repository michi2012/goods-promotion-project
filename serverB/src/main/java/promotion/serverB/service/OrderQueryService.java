package promotion.serverB.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderQueryService {

    private final StringRedisTemplate redisTemplate;

    // 상태(재고 수량)에 따라 만료 시간을 다르게 설정하는 캐시
    private final Cache<Long, Long> stockViewCache = Caffeine.newBuilder()
                                                             .expireAfter(new Expiry<Long, Long>() {
                                                                 @Override
                                                                 public long expireAfterCreate(Long key, Long value, long currentTime) {
                                                                     return calculateTtl(value);
                                                                 }

                                                                 @Override
                                                                 public long expireAfterUpdate(Long key, Long value, long currentTime, long currentDuration) {
                                                                     return calculateTtl(value);
                                                                 }

                                                                 @Override
                                                                 public long expireAfterRead(Long key, Long value, long currentTime, long currentDuration) {
                                                                     // 조회 시에는 기존 남은 시간을 그대로 유지
                                                                     return currentDuration;
                                                                 }

                                                                 private long calculateTtl(Long stock) {
                                                                     if (stock == 0L) {
                                                                         // 품절 상태: 5초 유지 (강력한 트래픽 방어)
                                                                         return TimeUnit.SECONDS.toNanos(5);
                                                                     }
                                                                     // 판매 중 상태: 500ms 유지 (빠른 상태 갱신)
                                                                     return TimeUnit.MILLISECONDS.toNanos(500);
                                                                 }
                                                             })
                                                             .build();

    private static final String ORDER_VIEW_PREFIX = "order:view:";
    private static final String STOCK_VIEW_PREFIX = "goods:view:stock:";

    public void updateOrderStatus(String orderId, String status) {
        redisTemplate.opsForValue().set(ORDER_VIEW_PREFIX + orderId + ":status", status);
        log.info("[OrderView] 주문 상태 업데이트: orderId={}, status={}", orderId, status);
    }

    public String getOrderStatus(String orderId) {
        String status = redisTemplate.opsForValue().get(ORDER_VIEW_PREFIX + orderId + ":status");
        return status != null ? status : "NOT_FOUND";
    }

    public void updateStockView(Long goodsId, Long remainingStock) {
        redisTemplate.opsForValue().set(STOCK_VIEW_PREFIX + goodsId, String.valueOf(remainingStock));
        // 업데이트 시 expireAfterUpdate가 호출되어 재고 수량에 맞는 새로운 만료 시간이 부여됨
        stockViewCache.put(goodsId, remainingStock);
    }

    public Long getStockView(Long goodsId) {
        Long cached = stockViewCache.getIfPresent(goodsId);
        if (cached != null) {
            return cached;
        }
        String val = redisTemplate.opsForValue().get(STOCK_VIEW_PREFIX + goodsId);
        Long stock = val != null ? Long.parseLong(val) : 0L;
        // 캐시에 없을 때 새로 넣으면 expireAfterCreate가 호출됨
        stockViewCache.put(goodsId, stock);
        return stock;
    }
}