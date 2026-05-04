package weverse.serverA.exception;

public record ErrorResponse(
        int status,
        String error,
        String message
) {}