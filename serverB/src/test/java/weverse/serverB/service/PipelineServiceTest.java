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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
}