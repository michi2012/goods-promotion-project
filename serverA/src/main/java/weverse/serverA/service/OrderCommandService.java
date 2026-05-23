package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Order;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OrderRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final SagaStateService sagaStateService;

    @Transactional
    public Order saveOrderAndDecreaseStock(PurchaseMessage message) {
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

        sagaStateService.initSagaState(
                message.traceId(), order.getId(),
                message.userId(), message.goodsId(), message.quantity()
        );

        return order;
    }
}
