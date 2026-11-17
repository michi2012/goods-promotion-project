package promotion.serverC.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverC.service.ReconciliationRunService;
import promotion.serverC.service.ReconciliationTime;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock
    private ReconciliationRunService runService;

    @InjectMocks
    private ReconciliationScheduler scheduler;

    @Test
    @DisplayName("어제(KST) 날짜로 스케줄 실행을 요청한다")
    void reconcilesYesterday() {
        scheduler.reconcileYesterday();

        verify(runService).runScheduled(LocalDate.now(ReconciliationTime.ZONE).minusDays(1));
    }

    @Test
    @DisplayName("실행 요청이 실패해도 예외를 밖으로 던지지 않는다 (스케줄러 스레드 보호)")
    void swallowsLaunchFailure() {
        when(runService.runScheduled(any())).thenThrow(new IllegalStateException("launch failed"));

        assertThatCode(scheduler::reconcileYesterday).doesNotThrowAnyException();
    }
}
