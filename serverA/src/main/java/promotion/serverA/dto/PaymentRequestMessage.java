package promotion.serverA.dto;

// payment-request 토픽 전용 메시지. 구매 접수 시점(PurchaseMessage)에는 금액을 모르므로,
// 주문 저장 단계에서 계산한 금액 스냅샷을 더해 serverC로 보낸다.
// JSON 필드명은 serverC PurchaseMessage와 맞춰야 한다 (필드 추가만 — 옛 serverC는 모르는 필드를 무시).
public record PaymentRequestMessage(
        String orderId,
        Long userId,
        Long goodsId,
        int quantity,
        long unitPrice,
        long totalAmount,
        String currency,
        String paymentMethod,
        String shippingAddress,
        String zipCode,
        String phoneNumber,
        String email,
        String deliveryMemo,
        String clientIp
) {
    public static PaymentRequestMessage of(PurchaseMessage message, long unitPrice, long totalAmount, String currency) {
        return new PaymentRequestMessage(
                message.orderId(),
                message.userId(),
                message.goodsId(),
                message.quantity(),
                unitPrice,
                totalAmount,
                currency,
                message.paymentMethod(),
                message.shippingAddress(),
                message.zipCode(),
                message.phoneNumber(),
                message.email(),
                message.deliveryMemo(),
                message.clientIp()
        );
    }
}
