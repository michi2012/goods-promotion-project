package promotion.serverC.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverC.repository.ReconciliationMismatchRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * 재현 ② 실패 지점부터 재시작 (명세 S-9, P-9).
 * 우리만 있는 승인 6건, chunk 2 → 불일치 쓰기 3번. 두 번째 쓰기에서 DB 오류.
 * 한 트랜잭션 구현은 전부 롤백돼 처음부터 다시 해야 하고, Job은 커밋된 첫 chunk 뒤부터 이어 처리한다.
 */
class ReconciliationRestartReproTest extends ReconciliationJobTestSupport {

    private static final int EVENTS = 6;

    @MockitoSpyBean
    private ReconciliationMismatchRepository mismatchRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        reset(mismatchRepository);
        for (int i = 1; i <= EVENTS; i++) {
            payment("o-" + i, "PAID", 1_000L * i, D.atTime(10, i), null);
        }
        pgFile(D); // PG에는 없음 → 6건 모두 ① 우리만 있음
        pgFile(D.minusDays(1));
    }

    @Test
    @DisplayName("단순 구현(한 트랜잭션): 중간 실패 시 앞서 처리한 건까지 전부 롤백 — 남는 진행이 없다")
    void naive_한트랜잭션은_전체롤백() {
        failOnWriteCall(2);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<Mismatch> chunk = new ArrayList<>();
            for (int i = 1; i <= EVENTS; i++) {
                chunk.add(new Mismatch(D, "o-" + i, "APPROVE", MismatchType.OURS_ONLY, 1_000L * i, null));
                if (chunk.size() == 2) {
                    mismatchRepository.insertIgnore(List.copyOf(chunk));
                    chunk.clear();
                }
            }
        })).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(mismatches(D)).isEmpty();
    }

    @Test
    @DisplayName("Job(chunk 커밋 + 재시작): 첫 chunk는 남고, 같은 파라미터로 다시 실행하면 실패 chunk부터 이어 최종 결과가 같다")
    void job_실패지점부터_재시작() throws Exception {
        failOnWriteCall(2);

        JobExecution failed = run(D);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(mismatches(D)).hasSize(2); // 첫 chunk는 커밋됨

        reset(mismatchRepository); // 장애 복구
        JobExecution restarted = run(D); // 같은 파라미터 → 재시작

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(stepNames(restarted)).doesNotContain("reconClearStep", "reconLoadDayStep"); // 완료 Step은 다시 안 돎
        assertThat(step(restarted, "reconOursApproveStep").getReadCount()).isEqualTo(EVENTS - 2); // 이미 처리한 2건은 건너뜀
        assertThat(mismatches(D)).hasSize(EVENTS)
                .allMatch(m -> m.type() == MismatchType.OURS_ONLY && m.eventType().equals("APPROVE"));
    }

    // n번째 불일치 쓰기에서 DB 오류를 낸다 (그 외는 실제 저장).
    private void failOnWriteCall(int n) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == n) {
                throw new DataAccessResourceFailureException("주입한 DB 오류");
            }
            return invocation.callRealMethod();
        }).when(mismatchRepository).insertIgnore(anyList());
    }

    private static List<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals(name)).findFirst().orElseThrow();
    }
}
