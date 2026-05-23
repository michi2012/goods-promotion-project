package weverse.serverB.dto;

public record OrderStatusMessage(
        String traceId,
        Long userId,
        String status
) {}
