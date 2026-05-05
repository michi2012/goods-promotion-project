package weverse.serverB.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 💡 429 Too Many Requests (서버 혼잡, 큐/메모리 초과)
    @ExceptionHandler(SystemOverloadException.class)
    public ResponseEntity<ErrorResponse> handleSystemOverload(SystemOverloadException e) {
        log.warn("🚨 [서버 B 배압 발동] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                             .body(new ErrorResponse(429, "TOO_MANY_REQUESTS", e.getMessage()));
    }

    // 💡 500 Internal Server Error (그 외 모든 서버 에러)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralError(Exception e) {
        log.error("❌ [서버 B 내부 오류 발생]", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .body(new ErrorResponse(500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}