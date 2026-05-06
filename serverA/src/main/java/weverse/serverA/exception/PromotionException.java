package weverse.serverA.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class PromotionException extends RuntimeException {
    private final HttpStatus status;

    public PromotionException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}
