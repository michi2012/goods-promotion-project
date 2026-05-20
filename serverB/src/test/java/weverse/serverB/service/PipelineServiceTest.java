package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import weverse.serverB.dto.PurchaseMessage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @InjectMocks
    private PipelineService pipelineService;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("멱등성: Redis에 이미 존재하는 traceId는 무시하고, 새로운 데이터만 파이프라인에 태운다.")
    void processBulkData_FiltersDuplicates() {
        // Given
        PurchaseMessage msg1 = new PurchaseMessage("trace-new", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        PurchaseMessage msg2 = new PurchaseMessage("trace-dup", 2L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        List<PurchaseMessage> messages = List.of(msg1, msg2);

        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // trace-new는 null(없음), trace-dup은 "OK"(있음) 반환 시뮬레이션
        given(valueOperations.multiGet(anyList())).willReturn(Arrays.asList(null, "OK"));

        // When
        pipelineService.processBulkData(messages);

        // Then
        // executePipelined가 한 번 호출되어야 함 (새로운 데이터가 있으므로)
        verify(redisTemplate, times(1)).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("멱등성: 모든 데이터가 이미 존재하는 중복 데이터라면 파이프라인 처리를 아예 수행하지 않는다.")
    void processBulkData_SkipsIfAllDuplicates() {
        // Given
        PurchaseMessage msg1 = new PurchaseMessage("trace-dup1", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        List<PurchaseMessage> messages = List.of(msg1);

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.multiGet(anyList())).willReturn(List.of("OK")); // 이미 존재함

        // When
        pipelineService.processBulkData(messages);

        // Then
        // 처리할 새 데이터가 없으므로 파이프라인 메서드가 호출되지 않아야 함
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("Caffeine 품절 캐시: markGoodsAsSoldOut 이후 isSoldOut은 Redis를 재조회하지 않고 캐시에서 true를 반환한다.")
    void isSoldOut_afterMarkGoodsAsSoldOut_hitsCaffeineWithoutRedisGet() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // When: 품절 처리 (내부적으로 Caffeine 캐시 및 Redis SET 호출)
        pipelineService.markGoodsAsSoldOut(1L);
        boolean result = pipelineService.isSoldOut(1L);

        // Then: Caffeine 캐시 히트 → Redis GET 호출 없음
        assertThat(result).isTrue();
        verify(valueOperations, never()).get(anyString());
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("Caffeine 품절 캐시: 품절 상태에서 getRemainingStock은 Redis 재고를 조회하지 않고 0을 반환한다.")
    void getRemainingStock_afterSoldOut_returnsZeroWithoutStockQuery() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        pipelineService.markGoodsAsSoldOut(1L);
        int stock = pipelineService.getRemainingStock(1L);

        // Then: isSoldOut Caffeine 히트 → 재고 Redis GET 호출 없이 0 반환
        assertThat(stock).isEqualTo(0);
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("Caffeine 품절 캐시: Redis에만 품절 플래그가 있을 때 첫 번째 호출에서 Caffeine에 동기화되고, 이후 Redis를 추가 조회하지 않는다.")
    void isSoldOut_whenOnlyInRedis_populatesLocalCacheAndStopsRedisQueries() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("goods:1:sold_out")).willReturn("true");

        // When: 첫 번째 호출 → Caffeine 미스 → Redis 조회 → Caffeine 동기화
        boolean first = pipelineService.isSoldOut(1L);

        // When: 두 번째 호출 → Caffeine 히트
        boolean second = pipelineService.isSoldOut(1L);

        // Then: Redis GET은 최초 1회만 호출
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        verify(valueOperations, times(1)).get("goods:1:sold_out");
    }
}