package weverse.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.service.PaymentService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaConsumer {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-request", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws Exception {
        PurchaseMessage msg = objectMapper.readValue(payload, PurchaseMessage.class);
        log.info("[Payment] payment-request 수신: traceId={}", msg.traceId());

        paymentService.processPayment(msg);
    }
}
