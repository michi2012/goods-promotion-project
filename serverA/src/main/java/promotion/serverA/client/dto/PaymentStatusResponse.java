package promotion.serverA.client.dto;

public record PaymentStatusResponse(
        String orderId,
        String status
) {}
