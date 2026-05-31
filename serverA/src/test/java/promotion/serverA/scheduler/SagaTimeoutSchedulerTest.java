package promotion.serverA.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import promotion.serverA.service.SagaOrchestratorService;
import promotion.serverA.service.SagaStateService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaTimeoutSchedulerTest {

    @InjectMocks
    private SagaTimeoutScheduler sagaTimeoutScheduler;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SagaStateService sagaStateService;

    @Mock
    private SagaOrchestratorService sagaOrchestratorService;

    @Mock
    private Cursor<String> cursor;

    @Test
    @DisplayName("3분이 초과된 Saga는 타임아웃 처리된다")
    void checkTimeouts_triggers_failure_for_expired_saga() {
        // given
        String orderId = "trace-1";
        String redisKey = "saga:state:" + orderId;
        long currentTime = System.currentTimeMillis();
        long expiredTime = currentTime - (4 * 60 * 1000L); // 4분 전

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false); // 1번 루프 후 종료
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(orderId)).willReturn(false);
        given(sagaStateService.getCreatedAt(orderId)).willReturn(expiredTime);

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaOrchestratorService, times(1)).handleSagaFailure(orderId, "TIMEOUT");
    }

    @Test
    @DisplayName("이미 실패한 Saga는 타임아웃 검사에서 제외된다")
    void checkTimeouts_skips_already_failed_saga() {
        // given
        String orderId = "trace-2";
        String redisKey = "saga:state:" + orderId;

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false);
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(orderId)).willReturn(true); // 실패 상태

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaStateService, never()).getCreatedAt(anyString());
        verify(sagaOrchestratorService, never()).handleSagaFailure(anyString(), anyString());
    }

    @Test
    @DisplayName("생성된 지 3분이 지나지 않은 Saga는 타임아웃 처리되지 않는다")
    void checkTimeouts_skips_not_expired_saga() {
        // given
        String orderId = "trace-3";
        String redisKey = "saga:state:" + orderId;
        long currentTime = System.currentTimeMillis();
        long validTime = currentTime - (1 * 60 * 1000L); // 1분 전

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false);
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(orderId)).willReturn(false);
        given(sagaStateService.getCreatedAt(orderId)).willReturn(validTime);

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaOrchestratorService, never()).handleSagaFailure(anyString(), anyString());
    }
}