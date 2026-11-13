package promotion.serverA.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.dto.PaymentRequestMessage;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.entity.Goods;
import promotion.serverA.entity.Order;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private SagaOrchestratorService sagaOrchestratorService;

    @InjectMocks
    private OrderCommandService orderCommandService;

    private static PurchaseMessage purchaseMessage() {
        return new PurchaseMessage(
                "trace-123", 1L, 4L, 2, "CARD", "주소", "12345", "010-0000-0000", "test@test.com", "메모", "127.0.0.1"
        );
    }

    private static Goods goodsWithPrice(long price) {
        return Goods.builder().name("상품").price(price).stock(10).build();
    }

    @Test
    @DisplayName("구매 메시지가 들어오면 주문을 저장하고 재고를 차감한 뒤 Saga 상태를 초기화하고 Outbox 이벤트를 저장한다")
    void saveOrderAndDecreaseStock_Success() {
        // given
        given(goodsRepository.findById(4L)).willReturn(Optional.of(goodsWithPrice(15_000L)));

        // when
        Order order = orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        verify(orderRepository).save(any(Order.class));
        verify(goodsRepository).decreaseStock(4L, 2);
        verify(sagaStateService).initSagaState(eq("trace-123"), any(), eq(1L), eq(4L), eq(2));
        verify(outboxEventService).save(eq("trace-123"), eq("order-status-update"), any());
        verify(outboxEventService).save(eq("trace-123"), eq("payment-request"), any(PaymentRequestMessage.class));

        assertThat(order.getOrderId()).isEqualTo("trace-123");
        assertThat(order.getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("주문 금액은 DB 상품 가격 × 수량으로 서버가 계산해 Order에 스냅샷한다")
    void saveOrderAndDecreaseStock_SnapshotsAmount() {
        // given
        given(goodsRepository.findById(4L)).willReturn(Optional.of(goodsWithPrice(15_000L)));

        // when
        Order order = orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        assertThat(order.getUnitPrice()).isEqualTo(15_000L);
        assertThat(order.getTotalAmount()).isEqualTo(30_000L);
        assertThat(order.getCurrency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("payment-request에는 계산된 금액과 통화가 담긴 PaymentRequestMessage를 저장한다")
    void saveOrderAndDecreaseStock_PaymentRequestCarriesAmount() {
        // given
        given(goodsRepository.findById(4L)).willReturn(Optional.of(goodsWithPrice(15_000L)));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

        // when
        orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        verify(outboxEventService).save(eq("trace-123"), eq("payment-request"), payload.capture());
        PaymentRequestMessage message = (PaymentRequestMessage) payload.getValue();
        assertThat(message.unitPrice()).isEqualTo(15_000L);
        assertThat(message.totalAmount()).isEqualTo(30_000L);
        assertThat(message.currency()).isEqualTo("KRW");
        assertThat(message.quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("가격이 0원인 상품이면 결제 요청 없이 기존 Saga 보상 흐름으로 실패 처리한다")
    void saveOrderAndDecreaseStock_ZeroPrice_FailsWithoutPaymentRequest() {
        // given
        given(goodsRepository.findById(4L)).willReturn(Optional.of(goodsWithPrice(0L)));

        // when
        orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        verify(sagaOrchestratorService).handleSagaFailure("trace-123", "INVALID_PRICE");
        verify(outboxEventService, never()).save(anyString(), eq("payment-request"), any());
    }

    @Test
    @DisplayName("상품이 없으면 가격을 알 수 없으므로 결제 요청 없이 실패 처리한다")
    void saveOrderAndDecreaseStock_GoodsNotFound_FailsWithoutPaymentRequest() {
        // given
        given(goodsRepository.findById(4L)).willReturn(Optional.empty());

        // when
        orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        verify(sagaOrchestratorService).handleSagaFailure("trace-123", "INVALID_PRICE");
        verify(outboxEventService, never()).save(anyString(), eq("payment-request"), any());
    }

    @Test
    @DisplayName("이미 처리된 orderId가 들어오면 저장 없이 기존 Order를 반환한다 (멱등 처리)")
    void saveOrderAndDecreaseStock_Duplicate_ReturnsExisting() {
        // given
        Order existingOrder = Order.builder().orderId("trace-123").build();
        given(orderRepository.findByOrderId("trace-123")).willReturn(Optional.of(existingOrder));

        // when
        Order result = orderCommandService.saveOrderAndDecreaseStock(purchaseMessage());

        // then
        assertThat(result).isSameAs(existingOrder);
        verify(orderRepository, never()).save(any());
        verify(goodsRepository, never()).decreaseStock(anyLong(), anyInt());
        verify(goodsRepository, never()).findById(anyLong());
    }
}
