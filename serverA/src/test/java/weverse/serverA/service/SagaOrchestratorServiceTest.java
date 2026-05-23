package weverse.serverA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import weverse.serverA.dto.SagaStateData;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OrderRepository;

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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private final String traceId = "trace-123";
    private final Long orderId = 1L;
    private final Long userId = 10L;
    private final Long goodsId = 100L;
    private final int quantity = 2;
    private final SagaStateData stateData = new SagaStateData(orderId, userId, goodsId, quantity);

    @Test
    @DisplayName("정상적으로 Saga 완료 로직을 수행하고 이벤트를 발행한다")
    void tryCompleteSaga_success() throws JsonProcessingException {
        // given
        given(sagaStateService.getSagaState(traceId)).willReturn(stateData);
        given(sagaStateService.isFailed(traceId)).willReturn(false);
        given(orderRepository.updateStatusIfPending(traceId, OrderStatus.PAID)).willReturn(1);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"dummy\":\"json\"}");

        // when
        sagaOrchestratorService.tryCompleteSaga(traceId);

        // then
        verify(orderRepository).updateStatusIfPending(traceId, OrderStatus.PAID);
        verify(kafkaTemplate).send(eq("order-status-update"), eq(traceId), anyString());
        verify(kafkaTemplate).send(eq("order-completed"), eq(traceId), anyString());
        verify(sagaStateService).deleteSagaState(traceId);
    }

    @Test
    @DisplayName("이미 실패 처리된 Saga는 완료를 시도하지 않고 무시한다")
    void tryCompleteSaga_ignores_if_already_failed() {
        // given
        given(sagaStateService.getSagaState(traceId)).willReturn(stateData);
        given(sagaStateService.isFailed(traceId)).willReturn(true);

        // when
        sagaOrchestratorService.tryCompleteSaga(traceId);

        // then
        verify(orderRepository, never()).updateStatusIfPending(anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("타임아웃 사유로 실패 시 EXPIRED 상태로 보상 트랜잭션을 수행한다")
    void handleSagaFailure_timeout_scenario() throws JsonProcessingException {
        // given
        given(sagaStateService.markFailedAndCheck(traceId)).willReturn(true);
        given(sagaStateService.getSagaState(traceId)).willReturn(stateData);
        given(redisStockService.getCurrentStock(goodsId)).willReturn(98L);
        given(objectMapper.writeValueAsString(any())).willReturn("{\"dummy\":\"json\"}");

        // when
        sagaOrchestratorService.handleSagaFailure(traceId, "TIMEOUT");

        // then
        verify(orderRepository).updateStatusIfPending(traceId, OrderStatus.EXPIRED);
        verify(redisStockService).releaseStock(goodsId, quantity);
        verify(redisStockService).releaseUserPurchase(userId, goodsId);
        verify(kafkaTemplate).send(eq("stock-snapshot"), eq(String.valueOf(goodsId)), anyString());
        verify(goodsRepository).increaseStockAtomically(goodsId, quantity);
        verify(kafkaTemplate).send(eq("order-status-update"), eq(traceId), anyString());
        verify(sagaStateService).deleteSagaState(traceId);
    }

    @Test
    @DisplayName("중복으로 실패 처리가 요청되면 멱등성을 보장하여 무시한다")
    void handleSagaFailure_ignores_duplicate_failure() {
        // given
        given(sagaStateService.markFailedAndCheck(traceId)).willReturn(false); // 이미 처리 중

        // when
        sagaOrchestratorService.handleSagaFailure(traceId, "ERROR");

        // then
        verify(sagaStateService, never()).getSagaState(traceId);
        verify(orderRepository, never()).updateStatusIfPending(anyString(), any());
        verify(redisStockService, never()).releaseStock(anyLong(), anyInt());
    }
}