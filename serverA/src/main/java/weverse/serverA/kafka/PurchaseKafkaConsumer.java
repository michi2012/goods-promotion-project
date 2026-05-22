package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseKafkaConsumer {

    private final OutboxRepository outboxRepository;
    private final GoodsRepository goodsRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "purchase_events", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void consume(String payload) throws Exception {
        PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
        log.info("[구매 처리 시작] TraceId: {} | UserId: {} | GoodsId: {}",
                message.traceId(), message.userId(), message.goodsId());

        RequestOutbox outbox = RequestOutbox.builder()
                .traceId(message.traceId())
                .userId(message.userId())
                .goodsId(message.goodsId())
                .quantity(message.quantity())
                .paymentMethod(message.paymentMethod())
                .shippingAddress(message.shippingAddress())
                .zipCode(message.zipCode())
                .phoneNumber(message.phoneNumber())
                .email(message.email())
                .deliveryMemo(message.deliveryMemo())
                .clientIp(message.clientIp())
                .status(OutboxStatus.SUCCESS)
                .build();

        outboxRepository.save(outbox);
        goodsRepository.decreaseStock(message.goodsId(), message.quantity());

        log.info("[구매 처리 완료] TraceId: {}", message.traceId());
    }
}
