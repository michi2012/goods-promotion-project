package weverse.serverB.exception;

public record ErrorResponse(
        int status,
        String error,
        String message
) {}