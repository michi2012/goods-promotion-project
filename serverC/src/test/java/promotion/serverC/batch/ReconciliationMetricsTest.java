package promotion.serverC.batch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import promotion.serverC.repository.ReconciliationMismatchRepository;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.service.ReconciliationTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationMetricsTest extends ReconciliationJobTestSupport {

    private static final String MISMATCH = "business_reconciliation_mismatch_total";
    private static final String FAILED = "business_reconciliation_job_failed_total";
    private static final String LAST_SUCCESS = "business_reconciliation_last_success_date_seconds";

    @Autowired
    private MeterRegistry contextRegistry; // Job에 리스너로 붙은 빈의 레지스트리 (컨텍스트 공유라 값은 전후 차이로 본다)

    @Autowired
    private ReconciliationMismatchRepository mismatchRepository;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Test
    @DisplayName("Job COMPLETED → 유형별 불일치 카운터가 건수만큼 증가 (Job 리스너 배선)")
    void completed_incrementsMismatchCounters() throws Exception {
        double oursBefore = mismatch(contextRegistry, MismatchType.OURS_ONLY);
        double pgBefore = mismatch(contextRegistry, MismatchType.PG_ONLY);
        payment("ours-1", "PAID", 1000L, D.atTime(10, 0), null);
        payment("ours-2", "PAID", 1000L, D.atTime(11, 0), null);
        pgFile(D, approve("pg-only-1", 500L, D.atTime(12, 0)));

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(mismatch(contextRegistry, MismatchType.OURS_ONLY) - oursBefore).isEqualTo(2);
        assertThat(mismatch(contextRegistry, MismatchType.PG_ONLY) - pgBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("Job FAILED(PG 파일 없음) → 실패 카운터 +1, 불일치 카운터는 그대로")
    void failed_incrementsFailedCounter() throws Exception {
        double failedBefore = contextRegistry.get(FAILED).counter().count();
        double oursBefore = mismatch(contextRegistry, MismatchType.OURS_ONLY);
        payment("ours-1", "PAID", 1000L, D.atTime(10, 0), null);

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(contextRegistry.get(FAILED).counter().count() - failedBefore).isEqualTo(1);
        assertThat(mismatch(contextRegistry, MismatchType.OURS_ONLY)).isEqualTo(oursBefore);
    }

    @Test
    @DisplayName("게이지 = 성공한 대사일의 끝(D+1 00:00 KST). 옛 날짜를 다시 성공시켜도 뒤로 가지 않는다")
    void gauge_endOfReconDate_neverMovesBack() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconciliationMetrics metrics = new ReconciliationMetrics(registry, mismatchRepository, runRepository);
        assertThat(registry.get(LAST_SUCCESS).gauge().value()).isNaN(); // 성공 기록 없음 → 알람 없음

        pgFile(D);
        metrics.afterJob(run(D));
        assertThat(registry.get(LAST_SUCCESS).gauge().value()).isEqualTo(endOfDay(D));

        pgFile(D.minusDays(1));
        metrics.afterJob(run(D.minusDays(1)));
        assertThat(registry.get(LAST_SUCCESS).gauge().value()).isEqualTo(endOfDay(D));
    }

    @Test
    @DisplayName("재기동: 게이지를 메타 테이블의 마지막 COMPLETED 대사일로 복원한다")
    void restoreLastSuccess_fromMetaTables() throws Exception {
        pgFile(D);
        run(D);
        run(D.plusDays(1)); // 파일 없음 → FAILED, 복원 대상 아님

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconciliationMetrics restarted = new ReconciliationMetrics(registry, mismatchRepository, runRepository);
        restarted.restoreLastSuccess();

        assertThat(registry.get(LAST_SUCCESS).gauge().value()).isEqualTo(endOfDay(D));
    }

    private static double mismatch(MeterRegistry registry, MismatchType type) {
        return registry.get(MISMATCH).tag("type", type.name()).counter().count();
    }

    private static double endOfDay(java.time.LocalDate date) {
        return date.plusDays(1).atStartOfDay(ReconciliationTime.ZONE).toEpochSecond();
    }
}
