package promotion.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import promotion.serverA.dto.SagaStateData;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SagaStateServiceTest {

    @InjectMocks
    private SagaStateService sagaStateService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private final String orderId = "trace-123";
    private final String stateKey = "saga:state:" + orderId;

    @BeforeEach
    void setUp() {
        // 명시적으로 필요한 동작에만 Stubbing 하도록 세팅 (strict stubbing 방지)
    }

    @Test
    @DisplayName("초기화 시 Redis Hash에 SagaState를 정상 저장한다")
    void initSagaState_success() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);

        // when
        sagaStateService.initSagaState(orderId, 1L, 10L, 100L, 2);

        // then
        verify(hashOperations).putAll(eq(stateKey), any(Map.class));
    }

    @Test
    @DisplayName("저장된 SagaStateData를 올바르게 매핑하여 반환한다")
    void getSagaState_returns_mapped_data() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        Map<Object, Object> mockData = Map.of(
                "orderEntityId", "1",
                "userId", "10",
                "goodsId", "100",
                "quantity", "2"
        );
        given(hashOperations.entries(stateKey)).willReturn(mockData);

        // when
        SagaStateData result = sagaStateService.getSagaState(orderId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.orderEntityId()).isEqualTo(1L);
        assertThat(result.userId()).isEqualTo(10L);
        assertThat(result.goodsId()).isEqualTo(100L);
        assertThat(result.quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("결제 완료 마킹 시 다른 상태 플래그가 미완료면 false를 반환한다")
    void markPaymentCompleted_returns_false_if_not_both_completed() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        Map<Object, Object> mockEntries = Map.of(
                "statusUpdateCompleted", "false", // 아직 미완료
                "paymentCompleted", "true"
        );
        given(hashOperations.entries(stateKey)).willReturn(mockEntries);

        // when
        boolean result = sagaStateService.markPaymentCompleted(orderId);

        // then
        verify(hashOperations).put(stateKey, "paymentCompleted", "true");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("두 상태가 모두 완료 처리되면 true를 반환한다")
    void markPaymentCompleted_returns_true_if_both_completed() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        Map<Object, Object> mockEntries = Map.of(
                "statusUpdateCompleted", "true",
                "paymentCompleted", "true"
        );
        given(hashOperations.entries(stateKey)).willReturn(mockEntries);

        // when
        boolean result = sagaStateService.markPaymentCompleted(orderId);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("최초 실패 마킹 시 1이 반환되며 true를 리턴한다")
    void markFailedAndCheck_first_try_returns_true() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.increment(stateKey, "failedOnce", 1L)).willReturn(1L);

        // when
        boolean result = sagaStateService.markFailedAndCheck(orderId);

        // then
        verify(hashOperations).put(stateKey, "failed", "true");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("이미 실패 마킹된 상태에서 다시 시도하면 false를 리턴한다")
    void markFailedAndCheck_duplicate_try_returns_false() {
        // given
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.increment(stateKey, "failedOnce", 1L)).willReturn(2L); // 1보다 큼

        // when
        boolean result = sagaStateService.markFailedAndCheck(orderId);

        // then
        verify(hashOperations, never()).put(eq(stateKey), eq("failed"), anyString());
        assertThat(result).isFalse();
    }
}