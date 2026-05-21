package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverB.client.ExternalApiClient;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.dto.ServerCResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamOffset;

@ExtendWith(MockitoExtension.class)
class QueueToCWorkerTest {

    @InjectMocks private QueueToCWorker worker;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ExternalApiClient externalApiClient;
    @Mock private ObjectMapper objectMapper;
    @Mock private StreamOperations<String, Object, Object> streamOperations;
    @Mock private ListOperations<String, String> listOperations;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(worker, "serverCUrl", "http://server-c");
        ReflectionTestUtils.setField(worker, "serverAUrl", "http://server-a");
    }

    @Test
    @DisplayName("성공/부분실패: 서버 C가 부분 실패를 응답하면, 실패한 건만 서버 A로 보상 지시를 내린다.")
    void dispatchToServerC_PartialFailure_TriggersCompensation() throws Exception {
        // Given
        given(redisTemplate.opsForStream()).willReturn(streamOperations);

        MapRecord<String, Object, Object> record = MapRecord.create("stream-key", Map.<Object, Object>of("payload", "json"))
                                                            .withId(RecordId.of("1234-0"));

        given(streamOperations.read(
                (Consumer) any(),
                (StreamReadOptions) any(),
                (StreamOffset[]) any()
        )).willReturn(List.of(record));

        PurchaseMessage msg = new PurchaseMessage("fail-trace", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        given(objectMapper.readValue("json", PurchaseMessage.class)).willReturn(msg);

        ServerCResponse cResponse = new ServerCResponse(false, "부분 실패 발생", List.of("fail-trace"));
        given(externalApiClient.sendBulkToServerC(anyString(), anyList()))
                .willReturn(new ResponseEntity<>(cResponse, HttpStatus.OK));

        // When
        worker.dispatchToServerC();

        // Then: 실패한 건에 대해 서버 A로 보상 지시가 전달되어야 함
        verify(externalApiClient).sendCompensationToServerA(anyString(), anyList());
        verify(streamOperations).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    @DisplayName("서버 A 다운: 보상 지시 통신조차 실패하면 Redis 재시도 큐에 안전하게 적재한다.")
    void dispatchToServerC_CompensationFails_SavesToRetryQueue() throws Exception {
        // Given
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        given(redisTemplate.opsForList()).willReturn(listOperations);

        MapRecord<String, Object, Object> record = MapRecord.create("key", Map.<Object, Object>of("payload", "json"))
                                                            .withId(RecordId.of("123-0"));

        given(streamOperations.read(
                (Consumer) any(),
                (StreamReadOptions) any(),
                (StreamOffset[]) any()
        )).willReturn(List.of(record));

        PurchaseMessage msg = new PurchaseMessage("fail-trace", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        given(objectMapper.readValue(anyString(), eq(PurchaseMessage.class))).willReturn(msg);
        given(objectMapper.writeValueAsString(any())).willReturn("retry-json");

        ServerCResponse cResponse = new ServerCResponse(false, "부분 실패 발생", List.of("fail-trace"));
        given(externalApiClient.sendBulkToServerC(anyString(), anyList()))
                .willReturn(new ResponseEntity<>(cResponse, HttpStatus.OK));

        // 서버 A로 보상 지시를 보낼 때 RuntimeException 발생 (테스트용)
        given(externalApiClient.sendCompensationToServerA(anyString(), anyList()))
                .willThrow(new RuntimeException("Server A Down"));

        // When
        worker.dispatchToServerC();

        // Then
        verify(listOperations).rightPush(eq("queue:retry_compensate_to_a"), eq("retry-json"));
    }

    @Test
    @DisplayName("장애 복구: Pending 상태인 고아 메시지를 다시 읽어와서 재처리한다.")
    void recoverPendingMessages_ProcessesOrphanedRecords() throws Exception {
        // Given
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        MapRecord<String, Object, Object> record = MapRecord.create("stream-key", Map.<Object, Object>of("payload", "json"))
                                                            .withId(RecordId.of("1234-0"));

        given(streamOperations.read(
                (Consumer) any(),
                (StreamReadOptions) any(),
                (StreamOffset[]) any()
        )).willReturn(List.of(record)); // 대기 중인 메시지가 있다고 가정

        PurchaseMessage msg = new PurchaseMessage("fail-trace", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        given(objectMapper.readValue("json", PurchaseMessage.class)).willReturn(msg);

        ServerCResponse cResponse = new ServerCResponse(true, "성공", List.of());
        given(externalApiClient.sendBulkToServerC(anyString(), anyList()))
                .willReturn(new ResponseEntity<>(cResponse, HttpStatus.OK));

        // When
        worker.recoverPendingMessages();

        // Then: 재처리가 성공해서 ACK 도장을 찍었는지 확인
        verify(streamOperations).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }
}