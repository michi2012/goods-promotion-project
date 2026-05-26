package weverse.serverB.dto;

public record OrderStatusMessage(
        String orderId,
        Long userId,
        String status
) {}
