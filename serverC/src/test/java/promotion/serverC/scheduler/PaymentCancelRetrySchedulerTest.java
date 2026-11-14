package promotion.serverC.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverC.service.PaymentCancelService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentCancelRetrySchedulerTest {

    @Mock
    private PaymentCancelService paymentCancelService;

    @InjectMocks
    private PaymentCancelRetryScheduler scheduler;

    @Test
    @DisplayName("주기마다 현재 시각 기준으로 재시도 대상을 처리한다")
    void retryDueCancels() {
        scheduler.retryDueCancels();

        verify(paymentCancelService).retryDue(any());
    }
}
