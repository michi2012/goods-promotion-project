package weverse.serverA.service.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import weverse.serverA.client.ExternalApiClient;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PushSchedulerTest {

    @InjectMocks
    private PushScheduler pushScheduler;

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxClaimer outboxClaimer;

    private List<RequestOutbox> mockPublishingList;

    @BeforeEach
    void setUp() {
        // @Value 필드 수동 주입
        ReflectionTestUtils.setField(pushScheduler, "serverBUrl", "http://localhost:8081");

        RequestOutbox outbox = RequestOutbox.builder().traceId("trace-1").status(OutboxStatus.PUBLISHING).build();
        ReflectionTestUtils.setField(outbox, "id", 1L); // ID 수동 주입
        mockPublishingList = List.of(outbox);
    }

    @Test
    @DisplayName("성공: 서버 B 통신에 200 OK를 받으면 데이터를 SENT 상태로 벌크 업데이트한다.")
    void pushToServerB_Success() {
        // Given
        given(outboxClaimer.claimSuccessRecords()).willReturn(mockPublishingList);
        given(externalApiClient.pushBulkToServerB(anyString(), anyList()))
                .willReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        // When
        pushScheduler.pushToServerB();

        // Then
        verify(outboxRepository).updateStatusByIds(OutboxStatus.SENT, List.of(1L));
    }

    @Test
    @DisplayName("실패(400): 서버 B가 Bad Request를 반환하면 좀비 데이터를 막기 위해 FAIL 처리한다.")
    void pushToServerB_BadRequest_ChangesToFail() {
        // Given
        given(outboxClaimer.claimSuccessRecords()).willReturn(mockPublishingList);
        given(externalApiClient.pushBulkToServerB(anyString(), anyList()))
                .willThrow(HttpClientErrorException.BadRequest.class);

        // When
        pushScheduler.pushToServerB();

        // Then
        verify(outboxRepository).updateStatusByIds(OutboxStatus.FAIL, List.of(1L));
    }

    @Test
    @DisplayName("장애(Timeout/5xx): 서버 B 통신 실패 시 배압을 켜고 SUCCESS로 롤백한다.")
    void pushToServerB_Exception_AppliesBackpressureAndRollbacks() {
        // Given
        given(outboxClaimer.claimSuccessRecords()).willReturn(mockPublishingList);
        given(externalApiClient.pushBulkToServerB(anyString(), anyList()))
                .willThrow(new RuntimeException("Connection Timeout"));

        // When
        pushScheduler.pushToServerB();

        // Then
        verify(outboxRepository).updateStatusByIds(OutboxStatus.SUCCESS, List.of(1L));

        // 배압(Backpressure) 스위치가 켜졌는지 리플렉션으로 검증
        boolean isBackpressureActive = (boolean) ReflectionTestUtils.getField(pushScheduler, "isBackpressureActive");
        assertThat(isBackpressureActive).isTrue();
    }
}