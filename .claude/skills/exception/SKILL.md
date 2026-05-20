---
name: exception
description: 예외 클래스 생성, GlobalExceptionHandler 수정, 에러 응답 형식 설계 시에만 활성화. 사용자가 예외, 에러, ExceptionHandler, 에러 응답, 상태 코드, 400, 404, 500 을 언급하거나 exception/ 폴더 작업 시 발동. 일반 비즈니스 로직이나 엔티티 작업에서는 발동하지 않음.
---

# 예외 처리 / 에러 응답 규칙

## 1. 비즈니스 예외 클래스 설계

### 기본 베이스 예외
```java
public abstract class BusinessException extends RuntimeException {
    private final String code;

    protected BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

### 도메인별 예외
```java
public class UserNotFoundException extends BusinessException {
    public UserNotFoundException(Long id) {
        super("USER_NOT_FOUND", "사용자를 찾을 수 없습니다. id=" + id);
    }
}

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super("DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다: " + email);
    }
}
```

## 2. 전역 예외 핸들러

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse> handleUserNotFound(UserNotFoundException e) {
        log.warn("User not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse> handleDuplicateEmail(DuplicateEmailException e) {
        log.warn("Duplicate email: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidation(MethodArgumentNotValidException e) {
        Map errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "잘못된 값",
                        (a, b) -> a
                ));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_FAILED", "입력값 검증 실패", errors));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONCURRENT_MODIFICATION", "다른 사용자가 먼저 수정했습니다"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleUnknown(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다"));
    }
}
```

## 3. 예외 처리 규칙

### Service 레이어
- 비즈니스 규칙 위반 시 도메인 예외를 던진다.
- `null` 반환으로 "없음" 표현 금지. `Optional` 또는 예외.

### 절대 금지
- ❌ `catch (Exception e) { e.printStackTrace(); }`
- ❌ 빈 catch
- ❌ 클라이언트 응답에 스택 트레이스 노출
- ❌ DB 에러 메시지 그대로 클라이언트 전달

## 4. HTTP 상태 코드 매핑

| 상황 | 상태 코드 |
|---|---|
| 정상 조회/처리 | 200 OK |
| 정상 생성 | 201 Created |
| 응답 본문 없음 | 204 No Content |
| 입력 검증 실패 | 400 Bad Request |
| 인증 실패 | 401 Unauthorized |
| 권한 없음 | 403 Forbidden |
| 리소스 없음 | 404 Not Found |
| 충돌 | 409 Conflict |
| 비즈니스 규칙 위반 | 422 Unprocessable Entity |
| 서버 오류 | 500 Internal Server Error |

## 5. 로깅 가이드
- 비즈니스 예외: `WARN`
- 시스템 예외: `ERROR` + 스택 트레이스
- 클라이언트 입력 오류: `WARN`

## 6. 자가 점검
- [ ] 모든 비즈니스 예외가 BusinessException 상속인가?
- [ ] 전역 핸들러에서 모든 예외 타입이 처리되는가?
- [ ] 스택 트레이스가 노출되지 않는가?
- [ ] 응답 형식이 일관된가?
- [ ] HTTP 상태 코드가 의미에 맞는가?