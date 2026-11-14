package promotion.serverC.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCancelFailureTest {

    private static final LocalDateTime NEXT = LocalDateTime.of(2026, 11, 14, 12, 10);

    @Test
    @DisplayName("자동 재시도 실패가 최대 횟수 미만이면 RETRYING을 유지하고 다음 시각을 잡는다")
    void autoRetryFailureBelowMax() {
        PaymentCancelFailure failure = PaymentCancelFailure.retrying("o", "e0", NEXT.minusMinutes(10));

        boolean exhausted = failure.recordAutoRetryFailure("e1", 6, NEXT);

        assertThat(exhausted).isFalse();
        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RETRYING);
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getNextRetryAt()).isEqualTo(NEXT);
        assertThat(failure.getLastError()).isEqualTo("e1");
    }

    @Test
    @DisplayName("자동 재시도 실패가 최대 횟수에 닿으면 EXHAUSTED가 되고 다음 시각이 없다")
    void autoRetryFailureReachesMax() {
        PaymentCancelFailure failure = PaymentCancelFailure.retrying("o", "e0", NEXT);
        for (int i = 0; i < 5; i++) {
            failure.recordAutoRetryFailure("e", 6, NEXT);
        }

        boolean exhausted = failure.recordAutoRetryFailure("last", 6, NEXT);

        assertThat(exhausted).isTrue();
        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getAttempts()).isEqualTo(6);
        assertThat(failure.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("RESOLVED 행에 DLT가 다시 오면 attempts를 유지한 채 RETRYING으로 돌아간다")
    void dltArrivalOnResolved() {
        PaymentCancelFailure failure = PaymentCancelFailure.retrying("o", "e0", NEXT);
        failure.recordAutoRetryFailure("e1", 6, NEXT);
        failure.markResolved();

        failure.recordDltArrival("again", NEXT);

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RETRYING);
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getNextRetryAt()).isEqualTo(NEXT);
    }

    @Test
    @DisplayName("EXHAUSTED 행에 DLT가 다시 오면 상태는 그대로이고 사유만 바뀐다")
    void dltArrivalOnExhausted() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted("o", "e0");

        failure.recordDltArrival("again", NEXT);

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getNextRetryAt()).isNull();
        assertThat(failure.getLastError()).isEqualTo("again");
    }

    @Test
    @DisplayName("관리자 재처리 실패는 횟수만 늘리고 상태를 바꾸지 않는다")
    void manualRetryFailure() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted("o", "e0");

        failure.recordManualRetryFailure("manual");

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getLastError()).isEqualTo("manual");
    }

    @Test
    @DisplayName("해결되면 RESOLVED가 되고 다음 시각이 없다")
    void markResolved() {
        PaymentCancelFailure failure = PaymentCancelFailure.retrying("o", "e0", NEXT);

        failure.markResolved();

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RESOLVED);
        assertThat(failure.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("사유는 500자로 자른다")
    void truncatesError() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted("o", "x".repeat(600));

        assertThat(failure.getLastError()).hasSize(500);
    }
}
