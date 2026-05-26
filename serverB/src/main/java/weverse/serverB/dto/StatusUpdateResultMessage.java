package weverse.serverB.dto;

public record StatusUpdateResultMessage(
        String orderId,
        boolean success,
        String errorMessage
) {}
