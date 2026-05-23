package weverse.serverB.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import weverse.serverB.dto.OrderStatusMessage;
import weverse.serverB.dto.StatusUpdateResultMessage;
import weverse.serverB.service.OrderQueryService;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStatusKafkaConsumer {

    private final OrderQueryService orderQueryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String RESULT_TOPIC = "status-update-result";

    @KafkaListener(topics = "order-status-update", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        OrderStatusMessage msg = objectMapper.readValue(payload, OrderStatusMessage.class);
        log.info("[OrderStatus] order-status-update 수신: traceId={}, userId={}, status={}",
                msg.traceId(), msg.userId(), msg.status());

        try {
            orderQueryService.updateOrderStatus(msg.traceId(), msg.status());

            // serverA로 성공 결과 produce (PENDING 상태 업데이트에 한해서만)
            if ("PENDING".equals(msg.status())) {
                String result = objectMapper.writeValueAsString(
                        new StatusUpdateResultMessage(msg.traceId(), true, null));
                kafkaTemplate.send(RESULT_TOPIC, msg.traceId(), result);
            }
        } catch (Exception e) {
            log.error("[OrderStatus] Redis 업데이트 실패: traceId={}", msg.traceId(), e);
            if ("PENDING".equals(msg.status())) {
                String result = objectMapper.writeValueAsString(
                        new StatusUpdateResultMessage(msg.traceId(), false, e.getMessage()));
                kafkaTemplate.send(RESULT_TOPIC, msg.traceId(), result);
            }
        }
    }
}
