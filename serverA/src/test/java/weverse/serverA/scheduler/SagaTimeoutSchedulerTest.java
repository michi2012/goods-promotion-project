package weverse.serverA.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import weverse.serverA.service.SagaOrchestratorService;
import weverse.serverA.service.SagaStateService;

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
    @DisplayName("10분이 초과된 Saga는 타임아웃 처리된다")
    void checkTimeouts_triggers_failure_for_expired_saga() {
        // given
        String traceId = "trace-1";
        String redisKey = "saga:state:" + traceId;
        long currentTime = System.currentTimeMillis();
        long expiredTime = currentTime - (11 * 60 * 1000L); // 11분 전

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false); // 1번 루프 후 종료
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(traceId)).willReturn(false);
        given(sagaStateService.getCreatedAt(traceId)).willReturn(expiredTime);

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaOrchestratorService, times(1)).handleSagaFailure(traceId, "TIMEOUT");
    }

    @Test
    @DisplayName("이미 실패한 Saga는 타임아웃 검사에서 제외된다")
    void checkTimeouts_skips_already_failed_saga() {
        // given
        String traceId = "trace-2";
        String redisKey = "saga:state:" + traceId;

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false);
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(traceId)).willReturn(true); // 실패 상태

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaStateService, never()).getCreatedAt(anyString());
        verify(sagaOrchestratorService, never()).handleSagaFailure(anyString(), anyString());
    }

    @Test
    @DisplayName("생성된 지 10분이 지나지 않은 Saga는 타임아웃 처리되지 않는다")
    void checkTimeouts_skips_not_expired_saga() {
        // given
        String traceId = "trace-3";
        String redisKey = "saga:state:" + traceId;
        long currentTime = System.currentTimeMillis();
        long validTime = currentTime - (5 * 60 * 1000L); // 5분 전

        given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
        given(cursor.hasNext()).willReturn(true, false);
        given(cursor.next()).willReturn(redisKey);

        given(sagaStateService.isFailed(traceId)).willReturn(false);
        given(sagaStateService.getCreatedAt(traceId)).willReturn(validTime);

        // when
        sagaTimeoutScheduler.checkTimeouts();

        // then
        verify(sagaOrchestratorService, never()).handleSagaFailure(anyString(), anyString());
    }
}