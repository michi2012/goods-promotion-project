package promotion.serverC.batch;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ReconciliationStartupCleaner.class)
class ReconciliationStartupCleanerTest extends ReconciliationJobTestSupport {

    @Autowired
    private ReconciliationStartupCleaner cleaner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("STARTED로 남은 실행·Step을 FAILED로 정리하고 실패 카운터를 올린다 → 같은 파라미터로 재시작된다")
    void failsInterruptedExecution_thenRestartable() throws Exception {
        // 서버가 Step 도중 내려간 상태를 흉내: Job·Step이 STARTED로 남음
        JobExecution interrupted = jobRepository.createJobExecution(ReconciliationJobConfig.JOB_NAME, params(D));
        interrupted.setStatus(BatchStatus.STARTED);
        interrupted.setStartTime(LocalDateTime.now());
        jobRepository.update(interrupted);
        StepExecution step = interrupted.createStepExecution("reconClearStep");
        step.setStatus(BatchStatus.STARTED);
        step.setStartTime(LocalDateTime.now());
        jobRepository.add(step);
        double failedBefore = meterRegistry.get("business_reconciliation_job_failed_total").counter().count();

        cleaner.failInterruptedExecutions();

        JobExecution cleaned = jobExplorer.getJobExecution(interrupted.getId());
        assertThat(cleaned.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(cleaned.getEndTime()).isNotNull();
        assertThat(cleaned.getStepExecutions()).extracting(StepExecution::getStatus).containsOnly(BatchStatus.FAILED);
        assertThat(jobExplorer.findRunningJobExecutions(ReconciliationJobConfig.JOB_NAME)).isEmpty();
        assertThat(meterRegistry.get("business_reconciliation_job_failed_total").counter().count() - failedBefore)
                .isEqualTo(1);

        pgFile(D);
        JobExecution restarted = run(D);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId()).isEqualTo(interrupted.getJobInstance().getInstanceId());
    }

    @Test
    @DisplayName("끝난 실행은 건드리지 않는다")
    void ignoresFinishedExecutions() throws Exception {
        pgFile(D);
        JobExecution completed = run(D);
        double failedBefore = meterRegistry.get("business_reconciliation_job_failed_total").counter().count();

        cleaner.failInterruptedExecutions();

        assertThat(jobExplorer.getJobExecution(completed.getId()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(meterRegistry.get("business_reconciliation_job_failed_total").counter().count())
                .isEqualTo(failedBefore);
    }
}
