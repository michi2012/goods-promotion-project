package weverse.serverC.dto;

import java.time.LocalDateTime;

public record PaymentResponse(
        String orderId,
        Long userId,
        Long goodsId,
        int quantity,
        String paymentMethod,
        String status,
        LocalDateTime createdAt
) {}
