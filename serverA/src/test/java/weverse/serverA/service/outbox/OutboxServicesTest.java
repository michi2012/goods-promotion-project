package weverse.serverA.service.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.PurchaseTask;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServicesTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private OutboxClaimer outboxClaimer;
    @InjectMocks private OutboxBatchService outboxBatchService;

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
        assertThat(claimedList.get(0).getStatus()).isEqualTo(OutboxStatus.PUBLISHING); // 상태 변경 확인
    }

    @Test
    @DisplayName("OutboxBatchService: 리스트 사이즈만큼 JdbcTemplate의 batchUpdate를 호출한다.")
    void batchInsert() {
        // Given
        PurchaseMessage msg = new PurchaseMessage("t1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "a@a.com", "memo", "ip");
        List<PurchaseTask> tasks = List.of(new PurchaseTask(msg, null));

        // When
        outboxBatchService.batchInsert(tasks);

        // Then
        verify(jdbcTemplate).batchUpdate(
                anyString(),
                eq(tasks),
                eq(1),
                any(ParameterizedPreparedStatementSetter.class)
        );
    }
}