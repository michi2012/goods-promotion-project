---
name: api
description: 새 REST 엔드포인트 생성, 컨트롤러 작성, DTO 설계, Bean Validation 적용 시에만 활성화. 사용자가 컨트롤러, 엔드포인트, API 만들기, DTO, URL, REST, 페이징 을 언급하거나 controller/, dto/ 폴더 작업 시 발동. 기존 서비스 로직만 수정하거나 엔티티/쿼리 작업에서는 발동하지 않음.
---

# REST API 엔드포인트 작성 패턴

## 0. 작업 시작 전

새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 유사한 기존 Controller 1개
- 유사한 기존 DTO 1개
- `application.yml` 설정

기존 명명 규칙, 패키지 구조, 응답 형식을 **그대로 따른다**. 새 패턴을 도입하지 마라.

## 1. 패키지 구조

기존 프로젝트 구조를 따른다. 일반적인 구조 예시:src/main/java/com/example/myapp/
├── user/
│   ├── controller/
│   │   └── UserController.java
│   ├── service/
│   │   └── UserService.java
│   ├── repository/
│   │   └── UserRepository.java
│   ├── domain/
│   │   └── User.java
│   ├── dto/
│   │   ├── request/
│   │   │   ├── UserCreateRequest.java
│   │   │   └── UserUpdateRequest.java
│   │   └── response/
│   │       └── UserResponse.java
│   └── exception/
│       └── UserNotFoundException.java
└── common/
    └── exception/
        ├── ApiResponse.java
        └── GlobalExceptionHandler.java

도메인 중심 또는 레이어 중심 중 **기존 프로젝트 방식**을 그대로 따른다.

## 2. Controller 작성 표준

```java@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
public class UserController {private final UserService userService;@PostMapping
public ResponseEntity<ApiResponse<UserResponse>> create(
        @Valid @RequestBody UserCreateRequest request) {
    UserResponse response = userService.create(request);
    return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(response));
}@GetMapping("/{id}")
public ApiResponse<UserResponse> getById(@PathVariable Long id) {
    return ApiResponse.success(userService.getById(id));
}@GetMapping
public ApiResponse<Page<UserResponse>> list(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
        Pageable pageable) {
    return ApiResponse.success(userService.list(pageable));
}@PutMapping("/{id}")
public ApiResponse<UserResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody UserUpdateRequest request) {
    return ApiResponse.success(userService.update(id, request));
}@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
}
}

### Controller 규칙
- URL은 복수 명사로 (`/users`, `/orders`). 동사 금지.
- HTTP 메서드의 의미를 따른다 (GET 멱등 + 안전, POST 생성, PUT 전체 교체, PATCH 부분 수정, DELETE 삭제).
- 상태 코드를 명확히: 201 Created, 204 No Content, 404 Not Found 등.
- 비즈니스 로직 없음. Service 호출과 변환만.
- `@Transactional` 절대 금지.
- `HttpServletRequest`를 Service에 전달 금지.
- Entity를 직접 반환 금지. 반드시 DTO로 변환.

## 3. DTO 작성 표준

### Request DTO
```javapublic record UserCreateRequest(
@NotBlank(message = "이메일은 필수입니다")
@Email(message = "올바른 이메일 형식이어야 합니다")
String email,    @NotBlank
    @Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다"
    )
    String password,    @NotBlank
    @Size(max = 50)
    String name
) {}

### Response DTO
```javapublic record UserResponse(
Long id,
String email,
String name,
LocalDateTime createdAt
) {
public static UserResponse from(User user) {
return new UserResponse(
user.getId(),
user.getEmail(),
user.getName(),
user.getCreatedAt()
);
}
}

### DTO 규칙
- Java 16+ 환경이면 `record` 사용.
- 비밀번호 등 민감 필드는 Response DTO에 절대 포함 금지.
- Entity → DTO 변환 메서드는 DTO 쪽 정적 팩토리 또는 별도 Mapper에 둔다.
- 같은 도메인에 대해 Create/Update/Response를 각각 분리.

## 4. 페이지네이션

목록 API는 항상 페이징 처리. `findAll()` 전체 반환 금지.

```java@GetMapping
public ApiResponse<Page<UserResponse>> list(
@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
Pageable pageable,
@RequestParam(required = false) String keyword) {
return ApiResponse.success(userService.search(keyword, pageable));
}

## 5. 공통 응답 래퍼

```javapublic record ApiResponse<T>(
boolean success,
T data,
ErrorDetail error
) {
public static <T> ApiResponse<T> success(T data) {
return new ApiResponse<>(true, data, null);
}public static ApiResponse<Void> error(String code, String message) {
    return new ApiResponse<>(false, null, new ErrorDetail(code, message, null));
}public record ErrorDetail(String code, String message, Object details) {}
}

## 6. API 버저닝
- URL 경로에 버전 포함: `/api/v1/...`
- 호환성 깨지는 변경은 v2로 분기.

## 7. 자가 점검
- [ ] Entity를 직접 노출하지 않았는가?
- [ ] DTO에 비밀번호/토큰이 없는가?
- [ ] Bean Validation 어노테이션을 적절히 사용했는가?
- [ ] 페이징이 필요한 곳에 적용했는가?
- [ ] 응답 형식이 프로젝트 표준과 일치하는가?
- [ ] Controller에 비즈니스 로직이 없는가?
- [ ] HTTP 상태 코드가 의미에 맞는가?