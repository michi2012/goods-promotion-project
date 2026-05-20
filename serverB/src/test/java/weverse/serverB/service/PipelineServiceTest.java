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
    @DisplayName("멱등성: 모든 데이터는 파이프라인을 통해 레디스로 전송되며, Lua 스크립트 내부에서 신규(1)와 중복(0)이 원자적으로 걸러진다.")
    void processBulkData_ExecutesPipelineAndFiltersViaScript() {
        // Given
        PurchaseMessage msg1 = new PurchaseMessage("trace-new", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        PurchaseMessage msg2 = new PurchaseMessage("trace-dup", 2L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        List<PurchaseMessage> messages = List.of(msg1, msg2);

        // Lua 스크립트가 반환할 결과를 모킹 (1L은 성공, 0L은 중복 처리됨을 의미)
        given(redisTemplate.executePipelined(any(SessionCallback.class)))
                .willReturn(Arrays.asList(1L, 0L));

        // When
        pipelineService.processBulkData(messages);

        // Then
        // 데이터가 존재하므로 파이프라인은 무조건 1번 호출되어야 함
        verify(redisTemplate, times(1)).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("멱등성: 입력된 데이터가 없으면 파이프라인 연산을 아예 수행하지 않는다.")
    void processBulkData_SkipsIfEmpty() {
        // Given
        List<PurchaseMessage> messages = List.of();

        // When
        pipelineService.processBulkData(messages);

        // Then
        // 빈 리스트가 들어오면 바로 리턴되므로 파이프라인이 호출되지 않아야 함
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