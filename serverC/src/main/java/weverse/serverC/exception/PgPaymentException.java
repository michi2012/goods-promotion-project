package weverse.serverC.exception;

public class PgPaymentException extends RuntimeException {
    public PgPaymentException(String message) {
        super(message);
    }
}