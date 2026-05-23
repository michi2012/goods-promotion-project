package weverse.serverA.dto;

public record PaymentResultMessage(
        String traceId,
        boolean success,
        String errorMessage
) {}
