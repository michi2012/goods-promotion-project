package promotion.serverC.dto;

import java.time.LocalDateTime;

public record PaymentResponse(
        String orderId,
        Long userId,
        Long goodsId,
        int quantity,
        // 결제 금액(원 단위)·통화. 금액 도입 이전 결제나 금액 없는 옛 메시지로 생성된 결제는 null.
        Long amount,
        String currency,
        String paymentMethod,
        String status,
        LocalDateTime createdAt
) {}
