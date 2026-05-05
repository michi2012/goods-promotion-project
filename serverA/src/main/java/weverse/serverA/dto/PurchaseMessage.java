package weverse.serverA.dto;

import weverse.serverA.dto.request.PurchaseRequest;

public record PurchaseMessage(
        String traceId,        // [핵심] 분산 추적 및 서버 C 멱등성 검증용 키
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
    // Server A에서 외부 요청(Request)을 내부 메시지(Message)로 변환할 때 사용하는 정적 팩토리 메서드
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
