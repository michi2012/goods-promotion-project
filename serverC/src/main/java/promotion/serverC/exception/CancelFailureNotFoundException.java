package promotion.serverC.exception;

public class CancelFailureNotFoundException extends RuntimeException {
    public CancelFailureNotFoundException(String orderId) {
        super("PG 취소 실패 기록을 찾을 수 없습니다: " + orderId);
    }
}
