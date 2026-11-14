package promotion.serverC.exception;

public class AdminForbiddenException extends RuntimeException {
    public AdminForbiddenException() {
        super("관리자만 사용할 수 있습니다");
    }
}
