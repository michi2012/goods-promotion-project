package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import weverse.serverA.dto.OrderStatusMessage;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Order;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProduceService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String PAYMENT_REQUEST_TOPIC = "payment-request";

    public void publishNextEvents(PurchaseMessage message, Order order) throws Exception {
        // 1. 상태 업데이트 이벤트 발행
        String statusMsg = objectMapper.writeValueAsString(
                new OrderStatusMessage(message.traceId(), message.userId(), "PENDING"));
        kafkaTemplate.send(ORDER_STATUS_TOPIC, message.traceId(), statusMsg);

        // 2. 결제 요청 이벤트 발행
        String paymentMsg = objectMapper.writeValueAsString(message);
        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, message.traceId(), paymentMsg);

        log.info("[Saga Phase1 카프카 발행 완료] TraceId: {} | orderId: {}", message.traceId(), order.getId());
    }
}