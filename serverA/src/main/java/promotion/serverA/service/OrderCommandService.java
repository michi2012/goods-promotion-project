package promotion.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverA.dto.OrderStatusMessage;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final SagaStateService sagaStateService;
    private final OutboxEventService outboxEventService;

    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String PAYMENT_REQUEST_TOPIC = "payment-request";

    @Transactional
    public Order saveOrderAndDecreaseStock(PurchaseMessage message) {
        return orderRepository.findByOrderId(message.orderId())
                              .map(existing -> {
                                  log.warn("[중복 메시지 무시] orderId={}", message.orderId());
                                  return existing;
                              })
                              .orElseGet(() -> processNewOrder(message));
    }

    private Order processNewOrder(PurchaseMessage message) {
        Order order = Order.builder()
                           .orderId(message.orderId())
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

        sagaStateService.initSagaState(
                message.orderId(), order.getId(),
                message.userId(), message.goodsId(), message.quantity()
        );

        outboxEventService.save(message.orderId(), ORDER_STATUS_TOPIC,
                new OrderStatusMessage(message.orderId(), message.userId(), "PENDING"));
        outboxEventService.save(message.orderId(), PAYMENT_REQUEST_TOPIC, message);

        return order;
    }
}
