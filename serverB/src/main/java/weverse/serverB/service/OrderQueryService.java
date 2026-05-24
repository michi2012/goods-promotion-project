package weverse.serverB.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    private final Cache<Long, Long> stockViewCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.SECONDS)
            .build();

    private static final String ORDER_VIEW_PREFIX = "order:view:";
    private static final String STOCK_VIEW_PREFIX = "goods:view:stock:";

    public void updateOrderStatus(String traceId, String status) {
        redisTemplate.opsForValue().set(ORDER_VIEW_PREFIX + traceId + ":status", status);
        log.info("[OrderView] 주문 상태 업데이트: traceId={}, status={}", traceId, status);
    }

    public String getOrderStatus(String traceId) {
        String status = redisTemplate.opsForValue().get(ORDER_VIEW_PREFIX + traceId + ":status");
        return status != null ? status : "NOT_FOUND";
    }

    public void updateStockView(Long goodsId, Long remainingStock) {
        redisTemplate.opsForValue().set(STOCK_VIEW_PREFIX + goodsId, String.valueOf(remainingStock));
        stockViewCache.put(goodsId, remainingStock);
    }

    public Long getStockView(Long goodsId) {
        Long cached = stockViewCache.getIfPresent(goodsId);
        if (cached != null) {
            return cached;
        }
        String val = redisTemplate.opsForValue().get(STOCK_VIEW_PREFIX + goodsId);
        Long stock = val != null ? Long.parseLong(val) : 0L;
        stockViewCache.put(goodsId, stock);
        return stock;
    }
}
