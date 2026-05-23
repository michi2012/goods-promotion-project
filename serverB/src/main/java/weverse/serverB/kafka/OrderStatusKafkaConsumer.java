package weverse.serverB.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverB.dto.OrderStatusMessage;
import weverse.serverB.service.OrderStatusEventHandler;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStatusKafkaConsumer {

    private final OrderStatusEventHandler orderStatusEventHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order-status-update", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        OrderStatusMessage msg = objectMapper.readValue(payload, OrderStatusMessage.class);
        log.info("[OrderStatus] order-status-update 수신: traceId={}, userId={}, status={}",
                msg.traceId(), msg.userId(), msg.status());

        orderStatusEventHandler.handleStatusUpdate(msg);
    }
}
