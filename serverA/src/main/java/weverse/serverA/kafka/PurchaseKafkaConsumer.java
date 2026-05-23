package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.OrderStatusMessage;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Order;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OrderRepository;
import weverse.serverA.service.SagaStateService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseKafkaConsumer {

    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final SagaStateService sagaStateService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String PAYMENT_REQUEST_TOPIC = "payment-request";

    @KafkaListener(topics = "purchase_events", groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void consume(String payload) throws Exception {
        PurchaseMessage message = objectMapper.readValue(payload, PurchaseMessage.class);
        log.info("[Saga Phase1 시작] TraceId: {} | UserId: {} | GoodsId: {}",
                message.traceId(), message.userId(), message.goodsId());

        // Step 1: DB 주문 INSERT + 재고 차감 (한 트랜잭션)
        Order order = Order.builder()
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
                .status(OrderStatus.PENDING)
                .build();

        orderRepository.save(order);
        goodsRepository.decreaseStock(message.goodsId(), message.quantity());

        // Step 2: SagaState Redis 초기화 + 소프트 홀드 설정
        sagaStateService.initSagaState(
                message.traceId(), order.getId(),
                message.userId(), message.goodsId(), message.quantity()
        );

        // Step 3: 병렬 produce
        String statusMsg = objectMapper.writeValueAsString(
                new OrderStatusMessage(message.traceId(), message.userId(), "PENDING"));
        kafkaTemplate.send(ORDER_STATUS_TOPIC, message.traceId(), statusMsg);
        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, message.traceId(), payload);

        log.info("[Saga Phase1 완료] TraceId: {} | orderId: {}", message.traceId(), order.getId());
    }
}
