package promotion.serverC.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import promotion.serverC.batch.ReconciliationJobConfig;
import promotion.serverC.exception.InvalidReconciliationDateException;
import promotion.serverC.exception.ReconciliationAlreadyRunningException;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.repository.ReconciliationRunRepository.RunRecord;
import promotion.serverC.service.ReconciliationRunService.Mode;
import promotion.serverC.service.ReconciliationRunService.Run;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationRunServiceTest {

    private static final LocalDate YESTERDAY = ReconciliationTime.now().toLocalDate().minusDays(1);

    @Mock
    private JobLauncher jobLauncher;
    @Mock
    private JobExplorer jobExplorer;
    @Mock
    private ReconciliationRunRepository runRepository;

    private ReconciliationRunService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationRunService(mock(Job.class), jobLauncher, jobExplorer, runRepository);
    }

    @Test
    @DisplayName("수동: 오늘 이후 날짜는 400 — 그날 PG 파일은 다음 날 생성된다")
    void manual_rejectsTodayOrLater() {
        LocalDate today = ReconciliationTime.now().toLocalDate();

        assertThatThrownBy(() -> service.runManually(today)).isInstanceOf(InvalidReconciliationDateException.class);
        assertThatThrownBy(() -> service.runManually(today.plusDays(1)))
                .isInstanceOf(InvalidReconciliationDateException.class);
    }

    @Test
    @DisplayName("수동: 실행 기록 없음 → reconDate만으로 새 실행")
    void manual_noRun_new() throws Exception {
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.empty());
        launchedWith(11L);

        Run run = service.runManually(YESTERDAY);

        assertThat(run).isEqualTo(new Run(11L, Mode.NEW));
        assertThat(launchedParams().getLocalDate(ReconciliationJobConfig.RECON_DATE)).isEqualTo(YESTERDAY);
        assertThat(launchedParams().getParameters()).doesNotContainKey(ReconciliationRunService.REQUESTED_AT);
    }

    @Test
    @DisplayName("수동: 실행 중 → 409, 실행하지 않는다")
    void manual_running_conflict() throws Exception {
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.of(new RunRecord(5L, BatchStatus.STARTED)));

        assertThatThrownBy(() -> service.runManually(YESTERDAY))
                .isInstanceOf(ReconciliationAlreadyRunningException.class);
        verify(jobLauncher, never()).run(any(), any());
    }

    @Test
    @DisplayName("수동: FAILED → 그 실행의 파라미터 그대로 재시작")
    void manual_failed_restart() throws Exception {
        JobParameters failedParams = new JobParametersBuilder()
                .addLocalDate(ReconciliationJobConfig.RECON_DATE, YESTERDAY)
                .addLocalDateTime(ReconciliationRunService.REQUESTED_AT, LocalDateTime.of(2026, 11, 17, 9, 0))
                .toJobParameters();
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.of(new RunRecord(5L, BatchStatus.FAILED)));
        when(jobExplorer.getJobExecution(5L)).thenReturn(new JobExecution(5L, failedParams));
        launchedWith(12L);

        Run run = service.runManually(YESTERDAY);

        assertThat(run).isEqualTo(new Run(12L, Mode.RESTART));
        assertThat(launchedParams()).isEqualTo(failedParams);
    }

    @Test
    @DisplayName("수동: COMPLETED → requestedAt을 붙여 새 실행 (결과 교체)")
    void manual_completed_newWithRequestedAt() throws Exception {
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.of(new RunRecord(5L, BatchStatus.COMPLETED)));
        launchedWith(13L);

        Run run = service.runManually(YESTERDAY);

        assertThat(run).isEqualTo(new Run(13L, Mode.NEW));
        assertThat(launchedParams().getLocalDateTime(ReconciliationRunService.REQUESTED_AT)).isNotNull();
    }

    @Test
    @DisplayName("수동: 확인과 실행 사이에 다른 실행이 시작돼 JobRepository가 거부하면 409")
    void manual_launcherRejectsRunning_conflict() throws Exception {
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.empty());
        when(jobLauncher.run(any(), any())).thenThrow(new JobExecutionAlreadyRunningException("running"));

        assertThatThrownBy(() -> service.runManually(YESTERDAY))
                .isInstanceOf(ReconciliationAlreadyRunningException.class);
    }

    @Test
    @DisplayName("스케줄: 실행 기록 없음 → 새 실행")
    void scheduled_noRun_new() throws Exception {
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.empty());
        launchedWith(21L);

        assertThat(service.runScheduled(YESTERDAY)).contains(new Run(21L, Mode.NEW));
    }

    @Test
    @DisplayName("스케줄: FAILED → 재시작")
    void scheduled_failed_restart() throws Exception {
        JobParameters failedParams = new JobParametersBuilder()
                .addLocalDate(ReconciliationJobConfig.RECON_DATE, YESTERDAY).toJobParameters();
        when(runRepository.findLatest(YESTERDAY)).thenReturn(Optional.of(new RunRecord(5L, BatchStatus.FAILED)));
        when(jobExplorer.getJobExecution(5L)).thenReturn(new JobExecution(5L, failedParams));
        launchedWith(22L);

        assertThat(service.runScheduled(YESTERDAY)).contains(new Run(22L, Mode.RESTART));
    }

    @Test
    @DisplayName("스케줄: COMPLETED·실행 중 → 건너뜀")
    void scheduled_completedOrRunning_skip() throws Exception {
        when(runRepository.findLatest(YESTERDAY))
                .thenReturn(Optional.of(new RunRecord(5L, BatchStatus.COMPLETED)))
                .thenReturn(Optional.of(new RunRecord(6L, BatchStatus.STARTING)));

        assertThat(service.runScheduled(YESTERDAY)).isEmpty();
        assertThat(service.runScheduled(YESTERDAY)).isEmpty();
        verify(jobLauncher, never()).run(any(), any());
    }

    // ---- helpers ----

    private void launchedWith(long executionId) throws Exception {
        when(jobLauncher.run(any(), any()))
                .thenAnswer(inv -> new JobExecution(executionId, inv.getArgument(1)));
    }

    private JobParameters launchedParams() throws Exception {
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(any(), captor.capture());
        return captor.getValue();
    }
}