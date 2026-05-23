package weverse.serverB.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import weverse.serverB.service.OrderQueryService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockSnapshotBuffer {

    private final OrderQueryService orderQueryService;

    private final ConcurrentHashMap<Long, Long> buffer = new ConcurrentHashMap<>();

    public void put(Long goodsId, Long remainingStock) {
        buffer.put(goodsId, remainingStock);
    }

    @Scheduled(fixedRate = 100)
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }

        Map<Long, Long> snapshot = new HashMap<>();
        buffer.forEach((goodsId, stock) -> {
            if (buffer.remove(goodsId, stock)) {
                snapshot.put(goodsId, stock);
            }
        });

        if (!snapshot.isEmpty()) {
            orderQueryService.batchUpdateStockView(snapshot);
            log.debug("[StockSnapshotBuffer] flush: {}건 Redis multiSet", snapshot.size());
        }
    }
}
