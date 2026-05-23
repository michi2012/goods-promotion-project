package weverse.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.OrderCompletedMessage;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCompletedConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order-completed", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        OrderCompletedMessage msg = objectMapper.readValue(payload, OrderCompletedMessage.class);
        log.info("[OrderCompleted] Saga 최종 확정 수신: traceId={}", msg.traceId());
    }
}
