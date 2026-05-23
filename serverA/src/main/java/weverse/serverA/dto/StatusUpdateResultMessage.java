package weverse.serverA.dto;

public record StatusUpdateResultMessage(
        String traceId,
        boolean success,
        String errorMessage
) {}
