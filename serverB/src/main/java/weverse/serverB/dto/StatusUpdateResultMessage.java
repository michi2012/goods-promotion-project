package weverse.serverB.dto;

public record StatusUpdateResultMessage(
        String traceId,
        boolean success,
        String errorMessage
) {}
