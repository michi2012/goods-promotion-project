package promotion.serverA.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.dto.PaymentCancelMessage;
import promotion.serverA.dto.SagaStateData;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaOrchestratorServiceTest {

    @InjectMocks
    private SagaOrchestratorService sagaOrchestratorService;

    @Mock
    private SagaStateService sagaStateService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private GoodsRepository goodsRepository;

    @Mock
    private RedisStockService redisStockService;

    @Mock
    private OutboxEventService outboxEventService;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final String orderId = "trace-123";
    private final Long dbOrderId = 1L;
    private final Long userId = 10L;
    private final Long goodsId = 100L;
    private final int quantity = 2;
    private final SagaStateData stateData = new SagaStateData(dbOrderId, userId, goodsId, quantity);

    @BeforeEach
    void setUp() {
        sagaOrchestratorService.initMetrics();
    }

    @Test
    @DisplayName("정상적으로 Saga 완료 로직을 수행하고 Outbox 이벤트를 저장한다")
    void tryCompleteSaga_success() {
        // given
        given(sagaStateService.getSagaState(orderId)).willReturn(stateData);
        given(sagaStateService.isFailed(orderId)).willReturn(false);
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID)).willReturn(1);
        given(sagaStateService.getCreatedAt(orderId)).willReturn(System.currentTimeMillis() - 500L);

        // when
        sagaOrchestratorService.tryCompleteSaga(orderId);

        // then
        verify(orderRepository).updateStatusIfPending(orderId, OrderStatus.PAID);
        verify(outboxEventService).save(eq(orderId), eq("order-status-update"), any());
        verify(outboxEventService).save(eq(orderId), eq("order-completed"), any());
        verify(sagaStateService).deleteSagaState(orderId);
        assertThat(meterRegistry.timer("business_payment_e2e_duration_seconds").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Saga 시작 시각(createdAt)을 알 수 없으면 종단 간 지연을 기록하지 않는다")
    void tryCompleteSaga_skips_e2e_metric_when_createdAt_missing() {
        // given
        given(sagaStateService.getSagaState(orderId)).willReturn(stateData);
        given(sagaStateService.isFailed(orderId)).willReturn(false);
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID)).willReturn(1);
        given(sagaStateService.getCreatedAt(orderId)).willReturn(0L);

        // when
        sagaOrchestratorService.tryCompleteSaga(orderId);

        // then
        verify(sagaStateService).deleteSagaState(orderId);
        assertThat(meterRegistry.timer("business_payment_e2e_duration_seconds").count()).isEqualTo(0);
    }

    @Test
    @DisplayName("이미 실패 처리된 Saga는 완료를 시도하지 않고 무시한다")
    void tryCompleteSaga_ignores_if_already_failed() {
        // given
        given(sagaStateService.getSagaState(orderId)).willReturn(stateData);
        given(sagaStateService.isFailed(orderId)).willReturn(true);

        // when
        sagaOrchestratorService.tryCompleteSaga(orderId);

        // then
        verify(orderRepository, never()).updateStatusIfPending(anyString(), any());
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("타임아웃 사유로 실패 시 EXPIRED 상태로 보상 트랜잭션을 수행하고 Outbox 이벤트를 저장한다")
    void handleSagaFailure_timeout_scenario() {
        // given
        given(sagaStateService.markFailedAndCheck(orderId)).willReturn(true);
        given(sagaStateService.getSagaState(orderId)).willReturn(stateData);
        given(redisStockService.getCurrentStock(goodsId)).willReturn(98L);

        // when
        sagaOrchestratorService.handleSagaFailure(orderId, "TIMEOUT");

        // then
        verify(orderRepository).updateStatusIfPending(orderId, OrderStatus.EXPIRED);
        verify(redisStockService).releaseStock(goodsId, quantity);
        verify(redisStockService).releaseUserPurchase(userId, goodsId);
        verify(goodsRepository).increaseStockAtomically(goodsId, quantity);
        verify(outboxEventService).save(eq(String.valueOf(goodsId)), eq("stock-snapshot"), any());
        verify(outboxEventService).save(eq(orderId), eq("order-status-update"), any());
        verify(sagaStateService).deleteSagaState(orderId);
    }

    @Test
    @DisplayName("중복으로 실패 처리가 요청되면 멱등성을 보장하여 무시한다")
    void handleSagaFailure_ignores_duplicate_failure() {
        // given
        given(sagaStateService.markFailedAndCheck(orderId)).willReturn(false);

        // when
        sagaOrchestratorService.handleSagaFailure(orderId, "ERROR");

        // then
        verify(sagaStateService, never()).getSagaState(orderId);
        verify(orderRepository, never()).updateStatusIfPending(anyString(), any());
        verify(redisStockService, never()).releaseStock(anyLong(), anyInt());
    }

    private Order order() {
        return Order.builder()
                .orderId(orderId)
                .userId(userId)
                .goodsId(goodsId)
                .quantity(quantity)
                .status(OrderStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("reconcileToPaid: Order를 직접 조회해 PAID로 보정하고 Outbox 이벤트를 저장한다 (Redis SagaState 불필요)")
    void reconcileToPaid_success() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(order()));
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID)).willReturn(1);

        // when
        sagaOrchestratorService.reconcileToPaid(orderId);

        // then
        verify(orderRepository).updateStatusIfPending(orderId, OrderStatus.PAID);
        verify(outboxEventService).save(eq(orderId), eq("order-status-update"), any());
        verify(redisStockService, never()).releaseStock(anyLong(), anyInt());
        verify(sagaStateService).deleteSagaState(orderId);
    }

    @Test
    @DisplayName("reconcileToPaid: 이미 처리된 주문(조건부 UPDATE 0건)이면 보정을 건너뛴다")
    void reconcileToPaid_alreadyProcessed_skips() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(order()));
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID)).willReturn(0);

        // when
        sagaOrchestratorService.reconcileToPaid(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
        verify(sagaStateService).deleteSagaState(orderId);
    }

    @Test
    @DisplayName("reconcileToFailed: Order를 직접 조회해 FAILED로 보정하고 재고를 복구한다 (Redis SagaState 불필요)")
    void reconcileToFailed_success() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(order()));
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.FAILED)).willReturn(1);
        given(redisStockService.getCurrentStock(goodsId)).willReturn(98L);

        // when
        sagaOrchestratorService.reconcileToFailed(orderId);

        // then
        verify(orderRepository).updateStatusIfPending(orderId, OrderStatus.FAILED);
        verify(redisStockService).releaseStock(goodsId, quantity);
        verify(redisStockService).releaseUserPurchase(userId, goodsId);
        verify(goodsRepository).increaseStockAtomically(goodsId, quantity);
        verify(outboxEventService).save(eq(String.valueOf(goodsId)), eq("stock-snapshot"), any());
        verify(outboxEventService).save(eq(orderId), eq("order-status-update"), any());
        verify(sagaStateService).deleteSagaState(orderId);
    }

    @Test
    @DisplayName("reconcileToFailed: 이미 처리된 주문(조건부 UPDATE 0건)이면 재고 보상을 건너뛴다")
    void reconcileToFailed_alreadyProcessed_skipsCompensation() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(order()));
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.FAILED)).willReturn(0);

        // when
        sagaOrchestratorService.reconcileToFailed(orderId);

        // then
        verify(redisStockService, never()).releaseStock(anyLong(), anyInt());
        verify(goodsRepository, never()).increaseStockAtomically(anyLong(), anyInt());
        verify(outboxEventService, never()).save(any(), any(), any());
        verify(sagaStateService).deleteSagaState(orderId);
    }

    private Order orderWithStatus(OrderStatus status) {
        return Order.builder().orderId(orderId).userId(userId).goodsId(goodsId).quantity(quantity).status(status).build();
    }

    @Test
    @DisplayName("EXPIRED 확정 뒤 결제 성공이 늦게 오면 망취소(payment-cancel)를 발행한다")
    void compensateLatePaymentSuccess_expired_publishes_cancel() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.EXPIRED)));

        // when
        sagaOrchestratorService.compensateLatePaymentSuccess(orderId);

        // then
        verify(outboxEventService).save(eq(orderId), eq("payment-cancel"), any(PaymentCancelMessage.class));
        assertThat(meterRegistry.get("saga.late_payment_cancel.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("FAILED 확정 뒤 결제 성공이 늦게 와도 망취소를 발행한다")
    void compensateLatePaymentSuccess_failed_publishes_cancel() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.FAILED)));

        // when
        sagaOrchestratorService.compensateLatePaymentSuccess(orderId);

        // then
        verify(outboxEventService).save(eq(orderId), eq("payment-cancel"), any(PaymentCancelMessage.class));
    }

    @Test
    @DisplayName("이미 PAID인 주문에 결제 성공이 다시 오면(중복 수신) 아무것도 하지 않는다")
    void compensateLatePaymentSuccess_paid_ignored() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.PAID)));

        // when
        sagaOrchestratorService.compensateLatePaymentSuccess(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("PENDING 주문(Saga 상태만 유실)은 망취소하지 않고 DB 재대사에 맡긴다")
    void compensateLatePaymentSuccess_pending_ignored() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.PENDING)));

        // when
        sagaOrchestratorService.compensateLatePaymentSuccess(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("주문이 없으면 보상 없이 넘어간다")
    void compensateLatePaymentSuccess_order_not_found() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.empty());

        // when
        sagaOrchestratorService.compensateLatePaymentSuccess(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("결과 유실 재대사: EXPIRED 주문이면 망취소를 발행하고 재대사 카운터만 올린다")
    void compensateLostPaymentResult_expired_publishes_cancel() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.EXPIRED)));

        // when
        sagaOrchestratorService.compensateLostPaymentResult(orderId);

        // then
        verify(outboxEventService).save(eq(orderId), eq("payment-cancel"), any(PaymentCancelMessage.class));
        assertThat(meterRegistry.get("saga.reconcile_cancel.total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("saga.late_payment_cancel.total").counter()).isNull();
    }

    @Test
    @DisplayName("결과 유실 재대사: FAILED 주문이면 망취소를 발행한다")
    void compensateLostPaymentResult_failed_publishes_cancel() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.FAILED)));

        // when
        sagaOrchestratorService.compensateLostPaymentResult(orderId);

        // then
        verify(outboxEventService).save(eq(orderId), eq("payment-cancel"), any(PaymentCancelMessage.class));
    }

    @Test
    @DisplayName("결과 유실 재대사: 종료 상태가 아닌 주문(PAID)이면 망취소하지 않는다")
    void compensateLostPaymentResult_paid_ignored() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.of(orderWithStatus(OrderStatus.PAID)));

        // when
        sagaOrchestratorService.compensateLostPaymentResult(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("결과 유실 재대사: 주문이 없으면 보상 없이 넘어간다")
    void compensateLostPaymentResult_order_not_found() {
        // given
        given(orderRepository.findByOrderId(orderId)).willReturn(Optional.empty());

        // when
        sagaOrchestratorService.compensateLostPaymentResult(orderId);

        // then
        verify(outboxEventService, never()).save(any(), any(), any());
    }
}