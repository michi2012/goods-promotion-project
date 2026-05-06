package weverse.serverA.exception;

import org.springframework.http.HttpStatus;

public class QueueFullException extends PromotionException {
    public QueueFullException() {
        super("현재 접속자가 많아 대기열 합류에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
