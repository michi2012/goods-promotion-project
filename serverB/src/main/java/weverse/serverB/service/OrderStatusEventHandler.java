package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import weverse.serverB.dto.OrderStatusMessage;
import weverse.serverB.dto.StatusUpdateResultMessage;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusEventHandler {

    private final OrderQueryService orderQueryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String RESULT_TOPIC = "status-update-result";

    public void handleStatusUpdate(OrderStatusMessage msg) throws Exception {
        try {
            // 1. 순수 Redis 업데이트
            orderQueryService.updateOrderStatus(msg.traceId(), msg.status());

            // 2. 비즈니스 규칙 처리 및 결과 발행
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