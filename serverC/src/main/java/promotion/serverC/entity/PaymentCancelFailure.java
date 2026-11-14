package promotion.serverC.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PG 취소 실패 기록 (주문당 한 행). 상태 전이는 docs/specs/user-refund.md "DB 변경" 표를 따른다.
 */
@Entity
@Table(
        name = "payment_cancel_failures",
        indexes = {
                @Index(name = "idx_payment_cancel_failures_status_next", columnList = "status, next_retry_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancelFailure extends BaseTimeEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CancelFailureStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    // Kafka 재시도를 다 쓰고 DLT에 도착한 취소 — 자동 재시도 대기
    public static PaymentCancelFailure retrying(String orderId, String error, LocalDateTime nextRetryAt) {
        PaymentCancelFailure failure = new PaymentCancelFailure();
        failure.orderId = orderId;
        failure.status = CancelFailureStatus.RETRYING;
        failure.attempts = 0;
        failure.nextRetryAt = nextRetryAt;
        failure.lastError = truncate(error);
        return failure;
    }

    // 재시도해도 소용없는 경우(결제 없음·취소할 수 없는 상태) — 바로 관리자 확인 대상
    public static PaymentCancelFailure exhausted(String orderId, String error) {
        PaymentCancelFailure failure = new PaymentCancelFailure();
        failure.orderId = orderId;
        failure.status = CancelFailureStatus.EXHAUSTED;
        failure.attempts = 0;
        failure.lastError = truncate(error);
        return failure;
    }

    // 같은 주문의 취소가 다시 DLT에 도착. RESOLVED였다면 다시 재시도 대기로, 나머지는 사유만 갱신한다 (attempts 유지).
    public void recordDltArrival(String error, LocalDateTime nextRetryAt) {
        if (status == CancelFailureStatus.RESOLVED) {
            status = CancelFailureStatus.RETRYING;
            this.nextRetryAt = nextRetryAt;
        }
        lastError = truncate(error);
    }

    // 자동 재시도 실패. 소진되면 EXHAUSTED로 바꾸고 true를 돌려준다.
    public boolean recordAutoRetryFailure(String error, int maxAttempts, LocalDateTime nextRetryAt) {
        attempts++;
        lastError = truncate(error);
        if (attempts >= maxAttempts) {
            status = CancelFailureStatus.EXHAUSTED;
            this.nextRetryAt = null;
            return true;
        }
        this.nextRetryAt = nextRetryAt;
        return false;
    }

    // 관리자 재처리 실패. 상태와 자동 재시도 일정은 그대로 둔다.
    public void recordManualRetryFailure(String error) {
        attempts++;
        lastError = truncate(error);
    }

    // 결제 상태가 취소할 수 없는 상태로 확인됨 — 관리자 확인 대상
    public void markExhausted(String error) {
        status = CancelFailureStatus.EXHAUSTED;
        nextRetryAt = null;
        lastError = truncate(error);
    }

    public void markResolved() {
        status = CancelFailureStatus.RESOLVED;
        nextRetryAt = null;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
