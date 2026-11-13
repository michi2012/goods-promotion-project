package promotion.serverC.dto;

// payment-request 메시지. unitPrice/totalAmount/currency는 금액 도입 시 추가된 필드로,
// 배포 중 옛 serverA가 보낸 메시지에는 없어 null로 역직렬화된다 (확장-축소 패턴 1단계).
public record PurchaseMessage(
        String orderId,
        Long userId,
        Long goodsId,
        int quantity,
        Long unitPrice,
        Long totalAmount,
        String currency,
        String paymentMethod,
        String shippingAddress,
        String zipCode,
        String phoneNumber,
        String email,
        String deliveryMemo,
        String clientIp
) {}
