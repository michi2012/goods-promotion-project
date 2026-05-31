package promotion.serverC.dto;

public record PaymentResultMessage(
        String orderId,
        boolean success,
        String errorMessage
) {}
