package promotion.serverB.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import promotion.serverB.dto.StockSnapshotMessage;
import promotion.serverB.service.OrderQueryService;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockSnapshotConsumer {

    private final OrderQueryService orderQueryService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "stock-snapshot", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        StockSnapshotMessage msg = objectMapper.readValue(payload, StockSnapshotMessage.class);
        orderQueryService.updateStockView(msg.goodsId(), msg.remainingStock());
        log.debug("[StockView] 재고 뷰 업데이트: goodsId={}, remaining={}", msg.goodsId(), msg.remainingStock());
    }
}
