package promotion.serverA.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.client.PaymentCheckStatus;
import promotion.serverA.client.PaymentQueryClient;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.repository.OrderRepository;
import promotion.serverA.service.SagaOrchestratorService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderReconciliationSchedulerTest {

    @InjectMocks
    private OrderReconciliationScheduler orderReconciliationScheduler;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentQueryClient paymentQueryClient;

    @Mock
    private SagaOrchestratorService sagaOrchestratorService;

    private Order order(String orderId) {
        return Order.builder()
                .orderId(orderId)
                .userId(1L)
                .goodsId(10L)
                .quantity(1)
                .status(OrderStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("방치된 PENDING 주문이 결제 완료로 확인되면 PAID로 보정한다")
    void reconcilePendingOrders_paid() {
        // given
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(order("order-1")));
        given(paymentQueryClient.checkPaymentStatus("order-1")).willReturn(PaymentCheckStatus.PAID);

        // when
        orderReconciliationScheduler.reconcilePendingOrders();

        // then
        verify(sagaOrchestratorService).reconcileToPaid("order-1");
        verify(sagaOrchestratorService, never()).reconcileToFailed(anyString());
    }

    @Test
    @DisplayName("방치된 PENDING 주문이 결제 미완료로 확인되면 FAILED로 보정한다")
    void reconcilePendingOrders_notPaid() {
        // given
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(order("order-2")));
        given(paymentQueryClient.checkPaymentStatus("order-2")).willReturn(PaymentCheckStatus.NOT_PAID);

        // when
        orderReconciliationScheduler.reconcilePendingOrders();

        // then
        verify(sagaOrchestratorService).reconcileToFailed("order-2");
        verify(sagaOrchestratorService, never()).reconcileToPaid(anyString());
    }

    @Test
    @DisplayName("결제 상태 조회 자체가 실패하면 이번 회차는 보정하지 않고 보류한다")
    void reconcilePendingOrders_unknown_skips() {
        // given
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of(order("order-3")));
        given(paymentQueryClient.checkPaymentStatus("order-3")).willReturn(PaymentCheckStatus.UNKNOWN);

        // when
        orderReconciliationScheduler.reconcilePendingOrders();

        // then
        verify(sagaOrchestratorService, never()).reconcileToPaid(anyString());
        verify(sagaOrchestratorService, never()).reconcileToFailed(anyString());
    }

    @Test
    @DisplayName("방치된 PENDING 주문이 없으면 조회조차 하지 않는다")
    void reconcilePendingOrders_empty_noop() {
        // given
        given(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(List.of());

        // when
        orderReconciliationScheduler.reconcilePendingOrders();

        // then
        verify(paymentQueryClient, never()).checkPaymentStatus(anyString());
    }
}
