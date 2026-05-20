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

public class InsufficientBalanceException extends BusinessException {
    public InsufficientBalanceException() {
        super("INSUFFICIENT_BALANCE", "잔액이 부족합니다");
    }
}
```

## 2. 전역 예외 핸들러

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 비즈니스 예외 (도메인별 매핑)
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFound(UserNotFoundException e) {
        log.warn("User not found: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateEmail(DuplicateEmailException e) {
        log.warn("Duplicate email: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    // 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "잘못된 값",
                        (a, b) -> a
                ));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_FAILED", "입력값 검증 실패", errors));
    }

    // 인증 실패 (Spring Security)
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("UNAUTHORIZED", "인증이 필요합니다"));
    }

    // 인가 실패
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "권한이 없습니다"));
    }

    // 낙관적 락 충돌
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONCURRENT_MODIFICATION", "다른 사용자가 먼저 수정했습니다. 다시 시도해주세요"));
    }

    // 일반 비즈니스 예외 (위에서 처리되지 않은 것)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    // 예상 못한 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "서버 오류가 발생했습니다"));
    }
}
```

## 3. 예외 처리 규칙

### Service 레이어
- 비즈니스 규칙 위반 시 적절한 도메인 예외를 던진다.
- 절대 `null`을 반환해서 "없음"을 표현하지 마라. `Optional` 또는 예외.

```java
public User getById(Long id) {
    return userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
}
```

### Controller 레이어
- try/catch 거의 사용 안 함. 전역 핸들러에 위임.
- 예외 변환이 필요한 특수한 경우만.

### 절대 금지
- ❌ `catch (Exception e) { e.printStackTrace(); }` — 로그 형식 통일, 무시 금지
- ❌ `catch (Exception e) { }` — 빈 catch 절대 금지
- ❌ 클라이언트 응답에 스택 트레이스 노출
- ❌ DB 에러 메시지를 그대로 클라이언트에 전달 (스키마 노출 위험)

## 4. HTTP 상태 코드 매핑

| 상황 | 상태 코드 |
|---|---|
| 정상 조회/처리 | 200 OK |
| 정상 생성 | 201 Created |
| 정상 처리, 응답 본문 없음 | 204 No Content |
| 입력 검증 실패 | 400 Bad Request |
| 인증 실패/누락 | 401 Unauthorized |
| 권한 없음 | 403 Forbidden |
| 리소스 없음 | 404 Not Found |
| 메서드 불일치 | 405 Method Not Allowed |
| 충돌 (중복, 동시성) | 409 Conflict |
| 비즈니스 규칙 위반 | 422 Unprocessable Entity |
| 요청 과다 | 429 Too Many Requests |
| 서버 오류 | 500 Internal Server Error |
| 외부 서비스 장애 | 502 Bad Gateway / 503 Service Unavailable |

## 5. 로깅 가이드

- 비즈니스 예외(예상된 흐름): `WARN` 또는 `INFO`
- 시스템 예외(예상 못한): `ERROR` + 스택 트레이스
- 외부 시스템 장애: `ERROR`
- 클라이언트 입력 오류: `WARN` (로그 폭주 방지)

## 6. 자가 점검
- [ ] 모든 비즈니스 예외가 BusinessException 상속인가?
- [ ] 전역 핸들러에서 모든 예외 타입이 처리되는가?
- [ ] 클라이언트에 스택 트레이스가 노출되지 않는가?
- [ ] 응답 형식이 일관된가?
- [ ] 적절한 HTTP 상태 코드를 반환하는가?
- [ ] 로그 레벨이 의미에 맞는가?