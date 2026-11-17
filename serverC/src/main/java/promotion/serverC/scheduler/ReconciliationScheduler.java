package promotion.serverC.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverC.service.ReconciliationRunService;
import promotion.serverC.service.ReconciliationTime;

import java.time.LocalDate;

/**
 * 매일 03:00(KST) 전일분 대사 (명세 P-1). Saga 미확정 건(최대 30분)과 00:10 PG 파일 생성이 끝난 뒤.
 * 실행은 비동기라 스케줄러 스레드를 붙잡지 않는다. Job 실패는 리스너가 메트릭으로 알린다 (ReconciliationJobFailed).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final ReconciliationRunService runService;

    @Scheduled(cron = "${reconciliation.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void reconcileYesterday() {
        LocalDate yesterday = LocalDate.now(ReconciliationTime.ZONE).minusDays(1);
        try {
            runService.runScheduled(yesterday);
        } catch (RuntimeException e) {
            // 실행조차 못 함 → 다음 날 미실행 알람(ReconciliationNotRun)으로 드러난다
            log.error("🚨 [Reconciliation] {} 스케줄 실행 실패", yesterday, e);
        }
    }
}
