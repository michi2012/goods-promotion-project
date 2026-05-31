package promotion.serverA.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.entity.Order;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private GoodsRepository goodsRepository;

    @Mock
    private SagaStateService sagaStateService;

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private OrderCommandService orderCommandService;

    @Test
    @DisplayName("구매 메시지가 들어오면 주문을 저장하고 재고를 차감한 뒤 Saga 상태를 초기화하고 Outbox 이벤트를 저장한다")
    void saveOrderAndDecreaseStock_Success() {
        // given
        PurchaseMessage message = new PurchaseMessage(
                "trace-123", 1L, 4L, 2, "CARD", "주소", "12345", "010-0000-0000", "test@test.com", "메모", "127.0.0.1"
        );

        // when
        Order order = orderCommandService.saveOrderAndDecreaseStock(message);

        // then
        verify(orderRepository).save(any(Order.class));
        verify(goodsRepository).decreaseStock(4L, 2);
        verify(sagaStateService).initSagaState(eq("trace-123"), any(), eq(1L), eq(4L), eq(2));
        verify(outboxEventService).save(eq("trace-123"), eq("order-status-update"), any());
        verify(outboxEventService).save(eq("trace-123"), eq("payment-request"), any());

        assertThat(order.getOrderId()).isEqualTo("trace-123");
        assertThat(order.getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("이미 처리된 orderId가 들어오면 저장 없이 기존 Order를 반환한다 (멱등 처리)")
    void saveOrderAndDecreaseStock_Duplicate_ReturnsExisting() {
        // given
        PurchaseMessage message = new PurchaseMessage(
                "trace-123", 1L, 4L, 2, "CARD", "주소", "12345", "010-0000-0000", "test@test.com", "메모", "127.0.0.1"
        );
        Order existingOrder = Order.builder().orderId("trace-123").build();
        given(orderRepository.findByOrderId("trace-123")).willReturn(Optional.of(existingOrder));

        // when
        Order result = orderCommandService.saveOrderAndDecreaseStock(message);

        // then
        assertThat(result).isSameAs(existingOrder);
        verify(orderRepository, never()).save(any());
        verify(goodsRepository, never()).decreaseStock(anyLong(), anyInt());
    }
}