package weverse.serverC.dto;

public record PaymentResultMessage(
        String traceId,
        boolean success,
        String errorMessage
) {}
