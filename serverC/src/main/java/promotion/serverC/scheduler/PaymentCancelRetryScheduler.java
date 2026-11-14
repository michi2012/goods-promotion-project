package promotion.serverC.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverC.service.PaymentCancelService;

import java.time.LocalDateTime;

/**
 * PG 취소 실패(payment_cancel_failures RETRYING)를 주기적으로 재시도한다.
 * 여러 인스턴스가 동시에 돌아도 행 단위 선점(조건부 UPDATE)으로 한 곳만 실행한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelRetryScheduler {

    private final PaymentCancelService paymentCancelService;

    @Scheduled(fixedDelayString = "${payment.cancel-retry.scheduler-delay-ms:60000}")
    public void retryDueCancels() {
        int processed = paymentCancelService.retryDue(LocalDateTime.now());
        if (processed > 0) {
            log.info("[PaymentCancelRetry] 재시도 {}건 처리", processed);
        }
    }
}
