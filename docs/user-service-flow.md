# user-service 흐름

회원 가입·로그인(JWT 발급)·토큰 재발급·프로필/결제수단 관리를 담당하는 모듈. Spring Security `UsernamePasswordAuthenticationFilter`를 커스텀한 `LoginFilter`가 인증의 핵심이다.

## 진입점

| 진입점 | 트리거 | 처리 클래스 |
|---|---|---|
| `POST /api/users` | 회원가입 | `UserController.createUser()` |
| `POST /api/users/admin-register` | 관리자 계정 생성 (`X-Role: ROLE_ADMIN` 헤더 필요) | `UserController.createAdmin()` |
| `POST /login` | 로그인 | `LoginFilter` (Security Filter, 컨트롤러 아님) |
| `POST /reissue` | Access 토큰 재발급 | `ReissueController.reissue()` |
| `GET/PUT /api/users/{id}`, `PUT /{id}/password`, `DELETE /{id}` | 프로필 조회/수정/비밀번호변경/탈퇴 | `UserController` |
| `POST/GET/PUT/DELETE /api/users/{userId}/payment-methods/**` | 결제수단 CRUD | `PaymentMethodController` |
| `GET /internal/api/users/{userId}`, `GET /internal/api/users/{userId}/payment-methods/default` | 내부 전용(다른 모듈 → user-service 직접 호출) | `InternalUserController` |

## 1. 회원가입 — `UserController.createUser()` → `UserService.register()`

1. `createUser()`는 `register(request, "ROLE_USER")`를 호출. `admin-register`는 헤더로 `ROLE_ADMIN` 여부를 확인(`X-Role` 헤더가 `ROLE_ADMIN`이 아니면 `AccessDeniedException`)한 뒤 `register(request, "ROLE_ADMIN")` 호출. `InternalUserController.createInternalUser()`는 `register(request, "ROLE_DRIVER")`를 호출한다(내부 전용 — 배송 기사 등 별도 역할, `arch-snapshot.md`의 "ROLE_USER / ROLE_ADMIN" 서술에는 없는 세 번째 역할).
2. `register()` — `existsByEmail()`/`existsByPhoneNumber()`로 중복 검사(`DuplicateEmailException`/`DuplicatePhoneNumberException`) 후 `BCryptPasswordEncoder`로 비밀번호 해싱해 `User` 저장.

## 2. 로그인 — `LoginFilter` (Spring Security 필터 체인, 컨트롤러 없음)

`SecurityConfig.filterChain()`에서 `LoginFilter`를 `UsernamePasswordAuthenticationFilter` 위치에 `addFilterAt()`으로 등록 — `/login` POST 요청을 컨트롤러가 아니라 이 필터가 가로챈다.

1. `attemptAuthentication()` — 요청 파라미터에서 username/password를 꺼내 `AuthenticationManager.authenticate()` 호출. 내부적으로 `CustomUserDetailsService.loadUserByUsername(email)`이 `UserRepository.findByEmail()`로 사용자를 찾아 `CustomUserDetails`로 감싸고, `BCryptPasswordEncoder`가 비밀번호를 검증한다(Spring Security 기본 `DaoAuthenticationProvider` 경로).
2. `successfulAuthentication()`:
   - `JWTUtil.createJwt("access", userId, role, 600000L)` — **10분** 만료 Access 토큰
   - `JWTUtil.createJwt("refresh", userId, role, 86400000L)` — **24시간** 만료 Refresh 토큰
   - `addRefreshEntity()` — Refresh 토큰을 `RefreshToken` 테이블에 저장
   - 응답: `Authorization: Bearer {access}` 헤더 + `refresh` 쿠키(`HttpOnly`, `SameSite=Strict`, `Secure=false`—로컬 전용, `maxAge=24h`)
3. `unsuccessfulAuthentication()` — 401만 반환.
4. JWT 클레임 구조(`JWTUtil.createJwt`): `category`("access"/"refresh") + `userId` + `role`. `getUsername()`/`getUserId()`/`getRole()`/`getCategory()`/`isExpired()`로 각각 파싱.

## 3. 토큰 재발급 — `ReissueController.reissue()`

1. 쿠키에서 `refresh` 값 추출 (없으면 400)
2. `jwtUtil.isExpired(refresh)` — 만료 시 `ExpiredJwtException` catch 후 400
3. `getCategory(refresh) == "refresh"` 확인 (access 토큰으로 reissue 시도 방지)
4. `refreshTokenRepository.existsByRefresh(refresh)` — DB에 없는 토큰(이미 사용/로그아웃됨)이면 400
5. 새 access/refresh 토큰 발급 → **기존 레코드 삭제(`deleteByRefresh`) 후 새로 저장** (Refresh Token Rotation) → 새 access는 헤더, 새 refresh는 쿠키로 응답

## 4. SecurityConfig — 인증 필터 체인 구성

- CORS: `cors.allowed-origins` 프로퍼티 기반, `Authorization` 헤더 노출 허용
- CSRF/formLogin/httpBasic 모두 비활성화 — SameSite 쿠키로 CSRF 방어를 대체
- `sessionCreationPolicy(STATELESS)` — 세션 미사용, 매 요청 JWT로 인증
- `authorizeHttpRequests`는 `/**`를 `permitAll()`로 전부 허용 — **실제 인증/인가는 이 필터 체인이 아니라 gateway-service의 `JwtAuthFilter`가 담당**한다(user-service 자체는 gateway가 검증을 마치고 넘겨준 `X-User-Id` 헤더를 신뢰). `UserController`/`PaymentMethodController`의 메서드가 `@RequestHeader("X-User-Id")`를 직접 받는 것도 이 구조 때문 — gateway를 거치지 않고 user-service에 직접 요청하면 인증 없이 통과된다는 뜻이므로, 서비스 간 통신은 내부망 경계에 의존한다.

## 5. 프로필/비밀번호/탈퇴 — `UserController` → `UserService`

공통 패턴: `getUserOrThrow(id)` → `validateOwner(user, authenticatedUserId)` — `user.getUserId()`(UUID)와 gateway가 주입한 `X-User-Id` 헤더 값이 다르면 `AccessDeniedException`(본인 정보만 접근 가능).
- `changePassword()`는 추가로 `bCryptPasswordEncoder.matches(oldPassword, ...)` 검증 후 `InvalidPasswordException` 또는 재해싱 저장.

## 6. 결제수단 관리 — `PaymentMethodController` → `PaymentMethodService`

- `registerPaymentMethod()` — 실제 PG 연동 없이 `dummy-billing-key-{UUID}` 형태의 가상 billing key 생성, 카드번호 앞자리로 카드사 추정(`inferCardIssuer`), `cardNumberMasked`로 마스킹 저장(원본 카드번호는 저장하지 않음). 첫 등록이면 자동으로 `isDefault=true`.
- `setDefaultPaymentMethod()` — `resetDefaultPaymentMethod(userId)` 벌크 UPDATE로 기존 기본 결제수단을 모두 해제한 뒤(`clearAutomatically=true`라 영속성 컨텍스트를 초기화하고 재조회) 대상 건만 `true`로 설정 — "기본 결제수단은 항상 1개" 불변식을 벌크 쿼리로 보장.
- `deletePaymentMethod()`/`setDefaultPaymentMethod()` 모두 `paymentMethod.getUser().getId().equals(userId)`로 소유자 검증.

## 7. 내부 API — `InternalUserController`

다른 모듈이 gateway를 거치지 않고 user-service를 직접 호출하는 경로(`/internal/api/users/**`, `arch-snapshot.md`의 API 표에는 없음 — cs-bot/codebot 등이 사용자·결제수단 정보를 조회할 때 사용하는 것으로 추정되는 내부 전용 엔드포인트).
- `GET /internal/api/users/{userId}` — `UserService.getUserByUserId()` (PK가 아닌 UUID `userId`로 조회)
- `GET /internal/api/users/{userId}/payment-methods/default` — `PaymentMethodService.getDefaultPaymentMethod()`로 기본 결제수단 + billing key 반환
- `POST /internal/api/users` — `ROLE_DRIVER`로 내부 사용자 생성
