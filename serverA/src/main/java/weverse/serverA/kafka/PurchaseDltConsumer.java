package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.DeadLetter;
import weverse.serverA.repository.DeadLetterRepository;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseDltConsumer {

    private final DeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "purchase_events.DLT",
            groupId = "${spring.kafka.consumer.group-id}.dlt",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(
            @Payload String payload,
            @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
    ) {
        log.warn("[DLT 수신] purchase_events.DLT | payload: {}", payload);

        String traceId = "UNKNOWN";
        Long goodsId = null;
        int quantity = 0;

        try {
            PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
            traceId = message.traceId();
            goodsId = message.goodsId();
            quantity = message.quantity();
        } catch (Exception e) {
            log.error("[DLT 파싱 실패] payload 원문 보존 후 저장 | 사유: {}", e.getMessage());
        }

        DeadLetter deadLetter = DeadLetter.builder()
                .traceId(traceId)
                .goodsId(goodsId)
                .quantity(quantity)
                .reason(truncate(exceptionMessage, 1000))
                .build();

        deadLetterRepository.save(deadLetter);
    }

    private String truncate(String str, int max) {
        if (str == null) return "Unknown Error";
        return str.length() > max ? str.substring(0, max) : str;
    }
}
