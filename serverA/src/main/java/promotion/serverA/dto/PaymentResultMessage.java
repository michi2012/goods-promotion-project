package promotion.serverA.dto;

public record PaymentResultMessage(
        String orderId,
        boolean success,
        String errorMessage
) {}
