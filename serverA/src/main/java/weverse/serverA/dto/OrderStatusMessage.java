package weverse.serverA.dto;

public record OrderStatusMessage(
        String traceId,
        Long userId,
        String status
) {}
