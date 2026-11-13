package promotion.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.service.SagaOrchestratorService;
import promotion.serverA.service.SagaStateService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SagaResultConsumerTest {

    private static final String ORDER_ID = "trace-123";

    @Mock
    private SagaStateService sagaStateService;

    @Mock
    private SagaOrchestratorService sagaOrchestratorService;

    private SagaResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SagaResultConsumer(sagaStateService, sagaOrchestratorService, new ObjectMapper());
    }

    private static String result(boolean success) {
        return "{\"orderId\":\"" + ORDER_ID + "\",\"success\":" + success + ",\"errorMessage\":" + (success ? "null" : "\"결제 거절\"") + "}";
    }

    @Test
    @DisplayName("진행 중인 Saga의 결제 성공은 플래그를 기록하고, 양쪽 완료 시 Saga를 확정한다")
    void paymentSuccess_activeSaga_marksAndCompletes() throws Exception {
        // given
        given(sagaStateService.exists(ORDER_ID)).willReturn(true);
        given(sagaStateService.markPaymentCompleted(ORDER_ID)).willReturn(true);

        // when
        consumer.consumePaymentResult(result(true));

        // then
        verify(sagaOrchestratorService).tryCompleteSaga(ORDER_ID);
        verify(sagaOrchestratorService, never()).compensateLatePaymentSuccess(anyString());
    }

    @Test
    @DisplayName("Saga가 이미 끝난 뒤 결제 성공이 오면 플래그를 기록하지 않고 늦은 결제 보상을 호출한다")
    void paymentSuccess_endedSaga_compensatesWithoutRecreatingKey() throws Exception {
        // given
        given(sagaStateService.exists(ORDER_ID)).willReturn(false);

        // when
        consumer.consumePaymentResult(result(true));

        // then
        verify(sagaOrchestratorService).compensateLatePaymentSuccess(ORDER_ID);
        verify(sagaStateService, never()).markPaymentCompleted(anyString());
        verify(sagaOrchestratorService, never()).tryCompleteSaga(anyString());
    }

    @Test
    @DisplayName("결제 실패 결과는 기존대로 Saga 실패 보상을 호출한다")
    void paymentFailure_handlesSagaFailure() throws Exception {
        // when
        consumer.consumePaymentResult(result(false));

        // then
        verify(sagaOrchestratorService).handleSagaFailure(ORDER_ID, "결제 거절");
        verify(sagaOrchestratorService, never()).compensateLatePaymentSuccess(anyString());
    }

    @Test
    @DisplayName("Saga가 이미 끝난 뒤 상태 갱신 성공이 오면 플래그를 기록하지 않고 무시한다")
    void statusUpdateSuccess_endedSaga_ignored() throws Exception {
        // given
        given(sagaStateService.exists(ORDER_ID)).willReturn(false);

        // when
        consumer.consumeStatusUpdateResult(result(true));

        // then
        verify(sagaStateService, never()).markStatusUpdateCompleted(anyString());
        verify(sagaOrchestratorService, never()).tryCompleteSaga(anyString());
        verify(sagaOrchestratorService, never()).compensateLatePaymentSuccess(anyString());
    }

    @Test
    @DisplayName("진행 중인 Saga의 상태 갱신 성공은 플래그를 기록한다")
    void statusUpdateSuccess_activeSaga_marks() throws Exception {
        // given
        given(sagaStateService.exists(ORDER_ID)).willReturn(true);
        given(sagaStateService.markStatusUpdateCompleted(ORDER_ID)).willReturn(false);

        // when
        consumer.consumeStatusUpdateResult(result(true));

        // then
        verify(sagaStateService).markStatusUpdateCompleted(ORDER_ID);
        verify(sagaOrchestratorService, never()).tryCompleteSaga(anyString());
    }
}
