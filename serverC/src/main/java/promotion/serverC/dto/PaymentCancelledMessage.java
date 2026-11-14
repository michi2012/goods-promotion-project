package promotion.serverC.dto;

import java.time.LocalDateTime;

// payment-cancelled: PG 취소가 확정됐다는 알림 (환불·망취소 모두). 멱등 키는 orderId.
public record PaymentCancelledMessage(String orderId, LocalDateTime cancelledAt) {
}
