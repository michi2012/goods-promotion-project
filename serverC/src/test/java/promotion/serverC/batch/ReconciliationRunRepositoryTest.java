package promotion.serverC.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.repository.ReconciliationRunRepository.RunRecord;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Job 실행이 남긴 BATCH_* 메타 행으로 조회한다 (reconDate 파라미터 저장 형식에 대한 의존을 고정).
class ReconciliationRunRepositoryTest extends ReconciliationJobTestSupport {

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Test
    @DisplayName("실행 기록이 없으면 비어 있다")
    void empty() {
        assertThat(runRepository.findLatest(D)).isEmpty();
        assertThat(runRepository.findLastCompletedDate()).isEmpty();
    }

    @Test
    @DisplayName("날짜별 가장 최근 실행 — requestedAt이 붙은 별개 인스턴스까지 날짜로 묶는다")
    void findLatest_acrossInstances() throws Exception {
        JobExecution failed = run(D); // 파일 없음 → FAILED
        assertThat(runRepository.findLatest(D)).contains(new RunRecord(failed.getId(), BatchStatus.FAILED));

        pgFile(D);
        JobExecution restarted = run(D);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runRepository.findLatest(D)).contains(new RunRecord(restarted.getId(), BatchStatus.COMPLETED));

        JobExecution rerun = jobLauncherTestUtils.launchJob(new JobParametersBuilder(params(D))
                .addLocalDateTime("requestedAt", LocalDateTime.of(2026, 11, 17, 9, 0))
                .toJobParameters());
        assertThat(runRepository.findLatest(D)).contains(new RunRecord(rerun.getId(), BatchStatus.COMPLETED));

        assertThat(runRepository.findLatest(D.minusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("마지막 COMPLETED 대사일 — 이후 날짜가 FAILED여도 COMPLETED 중 가장 늦은 날짜")
    void findLastCompletedDate() throws Exception {
        pgFile(D.minusDays(1));
        run(D.minusDays(1));
        pgFile(D);
        run(D);
        run(D.plusDays(1)); // 파일 없음 → FAILED

        assertThat(runRepository.findLastCompletedDate()).contains(D);
    }
}
