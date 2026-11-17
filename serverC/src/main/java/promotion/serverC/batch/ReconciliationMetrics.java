package promotion.serverC.batch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import promotion.serverC.repository.ReconciliationMismatchRepository;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.service.ReconciliationTime;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * 대사 메트릭 (docs/specs/pg-reconciliation-batch.md "메트릭·알람"). Job이 끝날 때 한 번 갱신한다 —
 * Step 안에서 세면 Step 재시도·재시작 때 두 번 셀 수 있다.
 *
 * 마지막 성공 게이지 값 = 대사일 D의 끝(D+1 00:00 KST) epoch 초. D 00:00으로 잡으면 정상 운영에서도
 * 다음 날 03:00 실행 직전 "time() - 값"이 51시간이 되어 미실행 알람(30시간)이 매일 울린다.
 * 성공 기록이 없으면 NaN(알람 없음). 재기동 시 Batch 메타 테이블에서 복원한다.
 */
@Component
@Slf4j
public class ReconciliationMetrics implements JobExecutionListener {

    private final ReconciliationMismatchRepository mismatchRepository;
    private final ReconciliationRunRepository runRepository;
    private final Map<MismatchType, Counter> mismatchCounters = new EnumMap<>(MismatchType.class);
    private final Counter jobFailed;
    private volatile double lastSuccessEpochSeconds = Double.NaN;

    public ReconciliationMetrics(MeterRegistry registry, ReconciliationMismatchRepository mismatchRepository,
                                 ReconciliationRunRepository runRepository) {
        this.mismatchRepository = mismatchRepository;
        this.runRepository = runRepository;
        for (MismatchType type : MismatchType.values()) {
            mismatchCounters.put(type, Counter.builder("business_reconciliation_mismatch_total")
                    .description("PG 대사 불일치 건수 (대사 1회 완료마다 유형별 건수만큼 증가)")
                    .tag("type", type.name())
                    .register(registry));
        }
        this.jobFailed = Counter.builder("business_reconciliation_job_failed_total")
                .description("PG 대사 Job 실패 횟수 (기동 시 끊긴 실행을 FAILED로 정리한 것 포함)")
                .register(registry);
        Gauge.builder("business_reconciliation_last_success_date_seconds", this, m -> m.lastSuccessEpochSeconds)
                .description("마지막으로 COMPLETED된 대사일의 끝(D+1 00:00 KST) epoch 초")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreLastSuccess() {
        runRepository.findLastCompletedDate().ifPresent(this::markSucceeded);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        LocalDate reconDate = jobExecution.getJobParameters().getLocalDate(ReconciliationJobConfig.RECON_DATE);
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            mismatchRepository.countByType(reconDate).forEach((type, count) -> mismatchCounters.get(type).increment(count));
            markSucceeded(reconDate);
        } else if (jobExecution.getStatus() == BatchStatus.FAILED) {
            log.error("🚨 [Reconciliation] {} 대사 실패: jobExecutionId={}", reconDate, jobExecution.getId());
            recordFailed();
        }
    }

    public void recordFailed() {
        jobFailed.increment();
    }

    // 옛 날짜를 다시 대사해도 게이지가 뒤로 가지 않는다.
    private synchronized void markSucceeded(LocalDate reconDate) {
        double endOfDay = reconDate.plusDays(1).atStartOfDay(ReconciliationTime.ZONE).toEpochSecond();
        if (Double.isNaN(lastSuccessEpochSeconds) || endOfDay > lastSuccessEpochSeconds) {
            lastSuccessEpochSeconds = endOfDay;
        }
    }
}
