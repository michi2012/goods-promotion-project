package promotion.serverA.dto;

public record StatusUpdateResultMessage(
        String orderId,
        boolean success,
        String errorMessage
) {}
