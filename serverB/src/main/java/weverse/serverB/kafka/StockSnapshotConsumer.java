package weverse.serverB.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverB.dto.StockSnapshotMessage;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockSnapshotConsumer {

    private final StockSnapshotBuffer stockSnapshotBuffer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "stock-snapshot", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        StockSnapshotMessage msg = objectMapper.readValue(payload, StockSnapshotMessage.class);
        stockSnapshotBuffer.put(msg.goodsId(), msg.remainingStock());
        log.debug("[StockSnapshotConsumer] 버퍼 적재: goodsId={}, remaining={}", msg.goodsId(), msg.remainingStock());
    }
}
