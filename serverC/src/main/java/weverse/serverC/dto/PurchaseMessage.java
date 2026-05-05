package weverse.serverC.dto;

public record PurchaseMessage(
        String traceId,        // 분산 추적 및 서버 C 멱등성 검증용 키
        Long userId,
        Long goodsId,
        int quantity,
        String paymentMethod,
        String shippingAddress,
        String zipCode,
        String phoneNumber,
        String email,
        String deliveryMemo,
        String clientIp
) {
    public static PurchaseMessage from(PurchaseRequest request, String traceId) {
        return new PurchaseMessage(
                traceId,
                request.userId(),
                request.goodsId(),
                request.quantity(),
                request.paymentMethod(),
                request.shippingAddress(),
                request.zipCode(),
                request.phoneNumber(),
                request.email(),
                request.deliveryMemo(),
                request.clientIp()
        );
    }
}
