package promotion.serverA.exception;

public class DuplicateOrderException extends BusinessException {
    public DuplicateOrderException() {
        super("이미 처리된 중복 주문 요청입니다.", "DUPLICATE_ORDER");
    }
}
