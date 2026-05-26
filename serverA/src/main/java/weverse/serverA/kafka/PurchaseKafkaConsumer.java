package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.service.OrderCommandService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseKafkaConsumer {

    private final OrderCommandService orderCommandService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "purchase_events", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
        log.info("[Saga Phase1 시작] TraceId: {}", message.orderId());

        orderCommandService.saveOrderAndDecreaseStock(message);
    }
}
