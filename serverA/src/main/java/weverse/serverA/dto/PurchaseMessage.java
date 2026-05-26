package weverse.serverA.dto;

import weverse.serverA.dto.request.PurchaseRequest;

public record PurchaseMessage(
        String orderId,
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
    public static PurchaseMessage from(PurchaseRequest request, String orderId) {
        return new PurchaseMessage(
                orderId,
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
