package weverse.serverC.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<String> handlePaymentNotFound(PaymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    // 서버 B가 보상 트랜잭션을 시작할 수 있도록 400 Bad Request를 정확히 반환
    @ExceptionHandler(PgPaymentException.class)
    public ResponseEntity<String> handlePgPaymentError(PgPaymentException e) {
        log.warn("💳 [PG 결제 실패] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    // 그 외 시스템 에러는 500으로 반환 (서버 B가 재시도하도록 유도)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralError(Exception e) {
        log.error("❌ [서버 C 내부 오류]", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("서버 C 내부 오류");
    }
}