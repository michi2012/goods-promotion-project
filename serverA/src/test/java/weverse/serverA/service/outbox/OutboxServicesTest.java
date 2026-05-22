package weverse.serverA.service.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OutboxServicesTest {

    @Mock private OutboxRepository outboxRepository;

    @InjectMocks private OutboxClaimer outboxClaimer;

    @Test
    @DisplayName("OutboxClaimer: SUCCESS 데이터를 조회한 뒤 즉시 PUBLISHING 상태로 변경하여 반환한다.")
    void claimSuccessRecords() {
        // Given
        RequestOutbox outbox = RequestOutbox.builder().status(OutboxStatus.SUCCESS).build();
        given(outboxRepository.findClaimableRecords(eq(OutboxStatus.SUCCESS.name()), anyInt()))
                .willReturn(List.of(outbox));

        // When
        List<RequestOutbox> claimedList = outboxClaimer.claimSuccessRecords();

        // Then
        assertThat(claimedList).hasSize(1);
        assertThat(claimedList.get(0).getStatus()).isEqualTo(OutboxStatus.PUBLISHING);
    }
}