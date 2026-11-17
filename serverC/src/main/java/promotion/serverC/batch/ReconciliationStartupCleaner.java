package promotion.serverC.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 서버가 대사 도중 내려가면 실행이 STARTED로 남아, 재시작도 새 실행도 "실행 중"으로 막힌다.
 * 기동 시 그런 실행을 FAILED로 정리해 다음 실행이 실패 Step의 마지막 커밋 위치부터 재시작하게 한다 (명세 "장애 시 동작").
 * 단일 인스턴스 전제 — 파드가 여럿이면 다른 파드에서 실제로 도는 실행까지 FAILED로 만든다 (BL-048).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationStartupCleaner {

    private static final String DESCRIPTION = "서버 재기동으로 중단된 실행을 기동 시 정리";

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final ReconciliationMetrics metrics;

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedExecutions() {
        for (JobExecution execution : jobExplorer.findRunningJobExecutions(ReconciliationJobConfig.JOB_NAME)) {
            LocalDateTime now = LocalDateTime.now();
            for (StepExecution step : execution.getStepExecutions()) {
                if (step.getStatus().isRunning()) {
                    step.setStatus(BatchStatus.FAILED);
                    step.setExitStatus(ExitStatus.FAILED.addExitDescription(DESCRIPTION));
                    step.setEndTime(now);
                    jobRepository.update(step);
                }
            }
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(ExitStatus.FAILED.addExitDescription(DESCRIPTION));
            execution.setEndTime(now);
            jobRepository.update(execution);
            log.warn("🚨 [Reconciliation] 중단된 실행을 FAILED로 정리: jobExecutionId={}, params={}",
                    execution.getId(), execution.getJobParameters());
            metrics.recordFailed();
        }
    }
}
