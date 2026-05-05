package weverse.serverA.service.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.repository.OutboxRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxManagerServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @InjectMocks
    private OutboxManagerService outboxManagerService;

    @Test
    @DisplayName("새벽 4시 청소 스케줄러: 데이터가 5000개 단위로 청크 삭제되고 루프를 잘 탈출하는지 검증")
    void cleanupPublishedOutbox_success() {
        // given
        // 첫 번째 호출 시 5000개 삭제(더 있음), 두 번째 호출 시 2000개 삭제(끝남)를 시뮬레이션
        when(outboxRepository.deleteOldOutboxData(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000)
                .thenReturn(2000);

        // when
        outboxManagerService.cleanupPublishedOutbox();

        // then
        // 1. Repository 메서드가 정확히 2번 호출되었는지 검증 (무한 루프 탈출 확인)
        verify(outboxRepository, times(2)).deleteOldOutboxData(any(LocalDateTime.class), eq(5000));

        // 2. 파라미터로 넘어간 시간이 '현재로부터 1일 전' 근처가 맞는지 검증
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxRepository, atLeastOnce()).deleteOldOutboxData(timeCaptor.capture(), eq(5000));

        LocalDateTime capturedTime = timeCaptor.getValue();
        LocalDateTime expectedTime = LocalDateTime.now().minusDays(1);

        // 실행 시간 오차를 감안하여 1분 이내의 차이인지 확인
        assertThat(capturedTime).isBetween(expectedTime.minusMinutes(1), expectedTime.plusMinutes(1));
    }

    @Test
    @DisplayName("좀비 메시지 복구 스케줄러: 15초 이상 멈춘 데이터를 정상적으로 찾아 원복하는지 검증")
    void recoverZombieMessages_success() {
        // given
        when(outboxRepository.recoverZombieMessages(any(LocalDateTime.class))).thenReturn(3);

        // when
        outboxManagerService.recoverZombieMessages();

        // then
        // 1. 파라미터로 넘어간 시간이 '현재로부터 15초 전' 근처가 맞는지 검증
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxRepository, times(1)).recoverZombieMessages(timeCaptor.capture());

        LocalDateTime capturedTime = timeCaptor.getValue();
        LocalDateTime expectedTime = LocalDateTime.now().minusSeconds(15);

        // 오차 감안 2초 이내 확인
        assertThat(capturedTime).isBetween(expectedTime.minusSeconds(2), expectedTime.plusSeconds(2));
    }

    @Test
    @DisplayName("좀비 메시지 복구 스케줄러: Repository 예외 발생 시 스케줄러가 죽지 않고 예외를 잡는지 검증")
    void recoverZombieMessages_handlesException() {
        // given
        when(outboxRepository.recoverZombieMessages(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB Connection Timeout"));

        // when & then
        // 예외가 던져져도 메서드 밖으로 전파되지 않고 내부 catch 블록에서 처리되어야 함 (테스트 통과)
        outboxManagerService.recoverZombieMessages();

        verify(outboxRepository, times(1)).recoverZombieMessages(any(LocalDateTime.class));
    }
}