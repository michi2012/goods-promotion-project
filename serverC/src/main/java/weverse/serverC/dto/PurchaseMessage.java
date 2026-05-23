package weverse.serverC.dto;

public record PurchaseMessage(
        String traceId,
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
) {}
