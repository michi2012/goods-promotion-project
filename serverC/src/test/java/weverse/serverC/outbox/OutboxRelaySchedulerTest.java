package weverse.serverC.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @InjectMocks
    private OutboxRelayScheduler scheduler;

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // TransactionTemplate이 실제로 동작하지 않도록 콜백 내부 로직만 그대로 실행하게 모킹
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        lenient().doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("릴레이 스케줄러: PENDING 이벤트를 조회하여 카프카 전송 성공 시 SENT로 변경한다.")
    void relay_success() {
        // given
        OutboxEvent event = OutboxEvent.create("1", "topicA", "{}");
        // 리플렉션을 사용하거나 테스트용 생성자로 ID 세팅 필요 (여기서는 mock으로 대체 가능)
        OutboxEvent spyEvent = spy(event);
        when(spyEvent.getId()).thenReturn(100L);

        given(repository.findPendingWithLock(500)).willReturn(List.of(spyEvent));

        // 카프카 비동기 결과 모킹 (성공 상황)
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        given(kafkaTemplate.send(eq("topicA"), eq("1"), eq("{}"))).willReturn(future);

        // when (스케줄러 실행)
        scheduler.relay();

        // 카프카 응답 도착 시뮬레이션
        future.complete(mock(SendResult.class));

        // then
        // 1. 상태가 PUBLISHING으로 바뀌었는지 확인
        verify(repository).updateStatusByIds(List.of(100L), OutboxStatus.PUBLISHING);
        // 2. 카프카 전송 호출 확인
        verify(kafkaTemplate).send("topicA", "1", "{}");
        // 3. 콜백 성공 로직: SENT로 업데이트 되었는지 확인
        verify(repository).updateStatusAndSentAt(eq(100L), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("릴레이 스케줄러: 카프카 전송 실패 시 PENDING으로 원복한다.")
    void relay_failure() {
        // given
        OutboxEvent spyEvent = spy(OutboxEvent.create("1", "topicA", "{}"));
        when(spyEvent.getId()).thenReturn(100L);

        given(repository.findPendingWithLock(500)).willReturn(List.of(spyEvent));

        // 카프카 비동기 결과 모킹 (실패 상황)
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        given(kafkaTemplate.send(any(), any(), any())).willReturn(future);

        // when
        scheduler.relay();

        // 에러 응답 도착 시뮬레이션
        future.completeExceptionally(new RuntimeException("Kafka Timeout"));

        // then
        // 콜백 실패 로직: PENDING으로 다시 돌아갔는지 확인
        verify(repository).updateStatus(eq(100L), eq(OutboxStatus.PENDING));
    }

    @Test
    @DisplayName("좀비 이벤트 구출 스케줄러가 정상 동작한다.")
    void rescueStuckEvents() {
        // given
        given(repository.rescueStuckEvents(eq(OutboxStatus.PUBLISHING), eq(OutboxStatus.PENDING), any(LocalDateTime.class)))
                .willReturn(5); // 5개 구출했다고 가정

        // when
        scheduler.rescueStuckEvents();

        // then
        verify(repository).rescueStuckEvents(eq(OutboxStatus.PUBLISHING), eq(OutboxStatus.PENDING), any(LocalDateTime.class));
    }
}