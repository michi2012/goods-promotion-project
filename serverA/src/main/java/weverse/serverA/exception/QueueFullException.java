package weverse.serverA.exception;

import org.springframework.http.HttpStatus;

public class QueueFullException extends PromotionException {

    public QueueFullException() {
        super("현재 접속자가 많아 대기열 합류에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    // 🔥 추가된 핵심 최적화 코드
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // 스택 트레이스 생성 비용(CPU 연산)을 원천 차단
    }
}