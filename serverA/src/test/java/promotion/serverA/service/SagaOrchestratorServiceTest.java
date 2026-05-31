package promotion.serverA.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.dto.SagaStateData;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

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

    private final String orderId = "trace-123";
    private final Long dbOrderId = 1L;
    private final Long userId = 10L;
    private final Long goodsId = 100L;
    private final int quantity = 2;
    private final SagaStateData stateData = new SagaStateData(dbOrderId, userId, goodsId, quantity);

    @Test
    @DisplayName("정상적으로 Saga 완료 로직을 수행하고 Outbox 이벤트를 저장한다")
    void tryCompleteSaga_success() {
        // given
        given(sagaStateService.getSagaState(orderId)).willReturn(stateData);
        given(sagaStateService.isFailed(orderId)).willReturn(false);
        given(orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID)).willReturn(1);

        // when
        sagaOrchestratorService.tryCompleteSaga(orderId);

        // then
        verify(orderRepository).updateStatusIfPending(orderId, OrderStatus.PAID);
        verify(outboxEventService).save(eq(orderId), eq("order-status-update"), any());
        verify(outboxEventService).save(eq(orderId), eq("order-completed"), any());
        verify(sagaStateService).deleteSagaState(orderId);
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
}