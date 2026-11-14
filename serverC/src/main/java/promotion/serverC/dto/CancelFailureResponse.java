package promotion.serverC.dto;

import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;

import java.time.LocalDateTime;

public record CancelFailureResponse(
        String orderId,
        CancelFailureStatus status,
        int attempts,
        LocalDateTime nextRetryAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CancelFailureResponse from(PaymentCancelFailure failure) {
        return new CancelFailureResponse(
                failure.getOrderId(),
                failure.getStatus(),
                failure.getAttempts(),
                failure.getNextRetryAt(),
                failure.getLastError(),
                failure.getCreatedAt(),
                failure.getUpdatedAt()
        );
    }
}
