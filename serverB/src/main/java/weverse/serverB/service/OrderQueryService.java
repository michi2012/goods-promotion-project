package weverse.serverB.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderQueryService {

    private final StringRedisTemplate redisTemplate;

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
    }

    public void batchUpdateStockView(Map<Long, Long> stockMap) {
        Map<String, String> redisMap = stockMap.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> STOCK_VIEW_PREFIX + e.getKey(),
                        e -> String.valueOf(e.getValue())
                ));
        redisTemplate.opsForValue().multiSet(redisMap);
    }

    public Long getStockView(Long goodsId) {
        String val = redisTemplate.opsForValue().get(STOCK_VIEW_PREFIX + goodsId);
        return val != null ? Long.parseLong(val) : 0L;
    }
}
