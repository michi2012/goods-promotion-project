package promotion.serverA.dto;

public record OrderStatusMessage(
        String orderId,
        Long userId,
        String status
) {}
