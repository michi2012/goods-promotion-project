package promotion.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverA.dto.OrderStatusMessage;
import promotion.serverA.dto.PaymentRequestMessage;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.entity.Goods;
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
    private final SagaOrchestratorService sagaOrchestratorService;

    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String PAYMENT_REQUEST_TOPIC = "payment-request";
    private static final String CURRENCY_KRW = "KRW";
    private static final String INVALID_PRICE_REASON = "INVALID_PRICE";

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
        // 금액은 클라이언트가 보낸 값이 아니라 서버가 DB 가격으로 계산한다 (위변조 방지).
        // 주문 시점 가격을 Order에 스냅샷해 이후 가격이 바뀌어도 이 주문의 금액은 고정된다.
        long unitPrice = goodsRepository.findById(message.goodsId())
                                        .map(Goods::getPrice)
                                        .orElse(0L);
        long totalAmount = Math.multiplyExact(unitPrice, message.quantity());

        Order order = Order.builder()
                           .orderId(message.orderId())
                           .userId(message.userId())
                           .goodsId(message.goodsId())
                           .quantity(message.quantity())
                           .unitPrice(unitPrice)
                           .totalAmount(totalAmount)
                           .currency(CURRENCY_KRW)
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

        // 가격 0원(가격 미설정 기존 상품 또는 상품 없음)이면 0원 결제가 PG로 나가지 않도록 결제 요청 없이
        // 기존 Saga 보상 흐름(FAILED + Redis/DB 재고·유저마킹 복구)을 그대로 재사용한다.
        if (unitPrice <= 0) {
            log.warn("[주문 실패] 가격 미설정 상품, 결제 요청 생략: orderId={}, goodsId={}", message.orderId(), message.goodsId());
            sagaOrchestratorService.handleSagaFailure(message.orderId(), INVALID_PRICE_REASON);
            return order;
        }

        outboxEventService.save(message.orderId(), PAYMENT_REQUEST_TOPIC,
                PaymentRequestMessage.of(message, unitPrice, totalAmount, CURRENCY_KRW));

        return order;
    }
}
