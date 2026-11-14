package promotion.serverC.exception;

public class InvalidCancelFailureStatusException extends RuntimeException {
    public InvalidCancelFailureStatusException(String status) {
        super("조회할 수 있는 상태는 RETRYING, EXHAUSTED입니다: " + status);
    }
}
