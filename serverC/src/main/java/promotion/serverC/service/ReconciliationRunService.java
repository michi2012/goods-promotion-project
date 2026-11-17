package promotion.serverC.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;
import promotion.serverC.batch.ReconciliationJobConfig;
import promotion.serverC.exception.InvalidReconciliationDateException;
import promotion.serverC.exception.ReconciliationAlreadyRunningException;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.repository.ReconciliationRunRepository.RunRecord;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 대사 Job 실행 (docs/specs/pg-reconciliation-batch.md "배치 Job" 실행·재시작).
 * 그 날짜의 마지막 실행이 FAILED면 같은 파라미터로 다시 실행 → Spring Batch 재시작(실패 Step의 마지막 커밋 위치부터).
 * COMPLETED면 수동 실행은 requestedAt을 붙여 새 실행(결과 교체), 스케줄 실행은 건너뛴다.
 *
 * 실행은 비동기다 (API는 실행 ID만 돌려주고, 스케줄러 스레드도 붙잡지 않는다). 비동기 실행기는 Bean으로 등록하지 않는다 —
 * Boot 자동 설정의 JobLauncher와 같은 타입이 둘이 되면 주입이 모호해진다.
 * synchronized는 같은 인스턴스 안의 동시 요청만 막는다 (단일 인스턴스 전제 — 다중 파드는 BL-048).
 */
@Service
@Slf4j
public class ReconciliationRunService {

    public static final String REQUESTED_AT = "requestedAt";

    public enum Mode { NEW, RESTART }

    public record Run(long jobExecutionId, Mode mode) {
    }

    private final Job job;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final ReconciliationRunRepository runRepository;

    @Autowired
    public ReconciliationRunService(Job pgReconciliationJob, JobRepository jobRepository, JobExplorer jobExplorer,
                                    ReconciliationRunRepository runRepository) throws Exception {
        this(pgReconciliationJob, asyncLauncher(jobRepository), jobExplorer, runRepository);
    }

    ReconciliationRunService(Job job, JobLauncher jobLauncher, JobExplorer jobExplorer,
                             ReconciliationRunRepository runRepository) {
        this.job = job;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.runRepository = runRepository;
    }

    // 관리자 수동 실행: 실행 중이면 409, COMPLETED면 새 실행
    public synchronized Run runManually(LocalDate reconDate) {
        if (!reconDate.isBefore(ReconciliationTime.now().toLocalDate())) {
            throw new InvalidReconciliationDateException(reconDate);
        }
        Optional<RunRecord> last = runRepository.findLatest(reconDate);
        if (last.isEmpty()) {
            return launch(reconDate, baseParams(reconDate).toJobParameters(), Mode.NEW);
        }
        BatchStatus status = last.get().status();
        if (status.isRunning()) {
            throw new ReconciliationAlreadyRunningException(reconDate);
        }
        if (status == BatchStatus.COMPLETED || status == BatchStatus.ABANDONED) {
            return rerun(reconDate);
        }
        return restart(reconDate, last.get());
    }

    // 매일 스케줄: 실행 중이거나 이미 COMPLETED면 건너뛴다.
    // ABANDONED(재시작 불가로 표시)는 대사가 안 된 날이라 새 실행 — 건너뛰면 알람 없이 묻힌다 (PR #64 리뷰 C1).
    public synchronized Optional<Run> runScheduled(LocalDate reconDate) {
        Optional<RunRecord> last = runRepository.findLatest(reconDate);
        if (last.isEmpty()) {
            return Optional.of(launch(reconDate, baseParams(reconDate).toJobParameters(), Mode.NEW));
        }
        BatchStatus status = last.get().status();
        if (status.isRunning() || status == BatchStatus.COMPLETED) {
            log.info("[Reconciliation] {} 스케줄 실행 건너뜀: 마지막 실행 {}", reconDate, status);
            return Optional.empty();
        }
        if (status == BatchStatus.ABANDONED) {
            return Optional.of(rerun(reconDate));
        }
        return Optional.of(restart(reconDate, last.get()));
    }

    // 새 JobInstance로 다시 계산 (결과 교체). COMPLETED·ABANDONED 인스턴스는 같은 파라미터로 다시 실행할 수 없다.
    private Run rerun(LocalDate reconDate) {
        JobParameters params = baseParams(reconDate)
                .addLocalDateTime(REQUESTED_AT, ReconciliationTime.now())
                .toJobParameters();
        return launch(reconDate, params, Mode.NEW);
    }

    // 실패한 실행의 파라미터(requestedAt 포함) 그대로 → 같은 JobInstance 재시작
    private Run restart(LocalDate reconDate, RunRecord failed) {
        JobParameters params = jobExplorer.getJobExecution(failed.jobExecutionId()).getJobParameters();
        return launch(reconDate, params, Mode.RESTART);
    }

    private Run launch(LocalDate reconDate, JobParameters params, Mode mode) {
        try {
            JobExecution execution = jobLauncher.run(job, params);
            log.info("[Reconciliation] {} 실행 시작: mode={}, jobExecutionId={}", reconDate, mode, execution.getId());
            return new Run(execution.getId(), mode);
        } catch (JobExecutionAlreadyRunningException e) {
            throw new ReconciliationAlreadyRunningException(reconDate);
        } catch (Exception e) {
            throw new IllegalStateException("대사 실행 실패: " + reconDate, e);
        }
    }

    private static JobParametersBuilder baseParams(LocalDate reconDate) {
        return new JobParametersBuilder().addLocalDate(ReconciliationJobConfig.RECON_DATE, reconDate);
    }

    private static JobLauncher asyncLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("recon-job-"));
        launcher.afterPropertiesSet();
        return launcher;
    }
}
