package promotion.serverB.exception;

public record ErrorResponse(
        int status,
        String error,
        String message
) {}