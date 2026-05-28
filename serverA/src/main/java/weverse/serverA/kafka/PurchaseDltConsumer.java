package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.service.dlt.DeadLetterService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseDltConsumer {

    private final DeadLetterService deadLetterService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "purchase_events.DLT",
            groupId = "${spring.kafka.consumer.group-id}.dlt",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void consume(
            @Payload String payload,
            @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
    ) {
        log.warn("[DLT 수신] purchase_events.DLT | payload: {}", payload);

        String orderId = "UNKNOWN";
        Long goodsId = null;
        int quantity = 0;

        try {
            PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
            orderId = message.orderId();
            goodsId = message.goodsId();
            quantity = message.quantity();
        } catch (Exception e) {
            log.error("[DLT 파싱 실패] payload 원문 보존 후 저장 | 사유: {}", e.getMessage());
        }

        deadLetterService.saveDeadLetter(orderId, goodsId, quantity, truncate(exceptionMessage, 1000));
    }

    private String truncate(String str, int max) {
        if (str == null) return "Unknown Error";
        return str.length() > max ? str.substring(0, max) : str;
    }
}
