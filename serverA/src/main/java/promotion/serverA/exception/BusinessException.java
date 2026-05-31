package promotion.serverA.exception;

import lombok.Getter;

// 💡 1. 최상위 비즈니스 예외 (이 예외들만 롤백시키지 않음)
@Getter
public abstract class BusinessException extends RuntimeException {
    private final String errorCode;

    public BusinessException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}