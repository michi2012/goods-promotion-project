package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Order;
import weverse.serverA.service.OrderCommandService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseKafkaConsumer {

    private final OrderCommandService orderCommandService;
    private final KafkaProduceService kafkaProduceService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "purchase_events", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
        log.info("[Saga Phase1 시작] TraceId: {}", message.traceId());

        // 1. DB 트랜잭션 처리
        Order order = orderCommandService.saveOrderAndDecreaseStock(message);

        // 2. 외부 네트워크 통신 (트랜잭션이 완전히 끝난 후 실행되므로 안전함)
        kafkaProduceService.publishNextEvents(message, order);
    }
}
