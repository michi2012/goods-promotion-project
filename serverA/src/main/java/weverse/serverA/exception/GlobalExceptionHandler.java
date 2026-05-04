package weverse.serverA.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 💡 비즈니스 에러를 한 번에 잡아서 처리 (상태 코드는 모두 400 또는 409로 통일하되, 내부 errorCode로 구분)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException e) {
        log.info("⚠️ 비즈니스 로직 거절: [{}] {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                             .body(Map.of("error", e.getErrorCode(), "message", e.getMessage()));
    }

    // 💡 그 외 진짜 시스템 장애 (500)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralError(Exception e) {
        log.error("❌ 서버 내부 에러 발생", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .body(Map.of("error", "INTERNAL_SERVER_ERROR", "message", "서버 내부 오류가 발생했습니다."));
    }
}