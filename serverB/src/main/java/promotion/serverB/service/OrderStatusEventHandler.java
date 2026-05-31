package promotion.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import promotion.serverB.dto.OrderStatusMessage;
import promotion.serverB.dto.StatusUpdateResultMessage;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusEventHandler {

    private final OrderQueryService orderQueryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String RESULT_TOPIC = "status-update-result";

    public void handleStatusUpdate(OrderStatusMessage msg) throws Exception {
        // 1. Redis 업데이트 — 실패 시 best-effort 실패 알림 후 정상 종료
        try {
            orderQueryService.updateOrderStatus(msg.orderId(), msg.status());
        } catch (Exception e) {
            log.error("[OrderStatus] Redis 업데이트 실패: orderId={}", msg.orderId(), e);
            if ("PENDING".equals(msg.status())) {
                String result = objectMapper.writeValueAsString(
                        new StatusUpdateResultMessage(msg.orderId(), false, e.getMessage()));
                kafkaTemplate.send(RESULT_TOPIC, msg.orderId(), result);
            }
            return;
        }

        // 2. Redis 성공 → 결과 발행 (동기). 실패 시 예외 전파 → consumer ErrorHandler 재시도 → DLT
        if ("PENDING".equals(msg.status())) {
            String result = objectMapper.writeValueAsString(
                    new StatusUpdateResultMessage(msg.orderId(), true, null));
            kafkaTemplate.send(RESULT_TOPIC, msg.orderId(), result).get(3, TimeUnit.SECONDS);
        }
    }
}