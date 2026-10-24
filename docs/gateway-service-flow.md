# gateway-service 흐름

모든 외부 트래픽의 단일 진입점. Spring Cloud Gateway 기반으로 JWT 인증(`GlobalFilter`)과 Redis 토큰버킷 Rate Limiting을 적용한 뒤 하위 서비스로 라우팅한다. 컨트롤러/서비스 계층이 따로 없고 `JwtAuthFilter` + `RateLimiterConfig` + `application.yml` 라우팅 설정 3가지가 전부다.

## 1. 요청 처리 순서

모든 요청은 아래 순서를 거친다(순서는 `JwtAuthFilter.getOrder() == -1`로 다른 필터보다 먼저 실행되도록 고정):

```
요청 → JwtAuthFilter (인증) → RequestRateLimiter 필터 (라우트별 설정) → 라우팅(lb://serverX)
```

## 2. `JwtAuthFilter` — 인증 (모든 요청에 적용되는 `GlobalFilter`)

1. `POST_WHITELIST = ["/login", "/reissue", "/api/users"]` — **POST 메서드에 한해서만** 화이트리스트 경로는 토큰 검증 없이 통과. (GET `/api/users/{id}`는 화이트리스트 대상이 아님 — 프로필 조회는 인증 필요.)
2. 화이트리스트가 아니면 `Authorization: Bearer {token}` 헤더 확인 — 없거나 형식이 틀리면 `401`.
3. `Jwts.parser().verifyWith(secretKey)`로 서명 검증 (user-service의 `JWTUtil`과 동일한 `spring.jwt.secret` 값을 공유해야 검증 가능 — 두 모듈이 물리적으로 같은 시크릿을 각자 설정으로 들고 있음).
4. `category` 클레임이 `"access"`가 아니면(즉 refresh 토큰으로 API 호출 시도) `401`.
5. 통과 시 `userId`/`role` 클레임을 꺼내 **기존 `X-User-Id`/`X-User-Role` 헤더를 강제로 제거한 뒤 재설정** — 클라이언트가 이 헤더들을 직접 위조해서 보내도 무시되고 서버가 검증한 값으로 덮어써진다. 이 헤더가 하위 서비스(`UserController`의 `@RequestHeader("X-User-Id")` 등)가 신뢰하는 유일한 인증 정보.
6. JWT 파싱 예외(`JwtException`, `IllegalArgumentException`, 만료 포함)는 모두 `401`로 귀결.

## 3. Rate Limiting — `RateLimiterConfig.ipKeyResolver()` + 라우트별 `RequestRateLimiter`

- 키 결정: `X-Forwarded-For` 헤더 우선 사용(ALB 뒤에서는 `getRemoteAddress()`가 ALB 내부 IP를 반환하므로), 없으면 `getRemoteAddress()`, 그마저 없으면 `"unknown"` — **IP 단위** 버킷.
- 버킷은 Redis에 저장(Spring Cloud Gateway의 `RedisRateLimiter` 내장 구현, 별도 코드 없음).
- 라우트별 설정(`application.yml`):

| 라우트 id | 대상 | 경로 | replenishRate | burstCapacity |
|---|---|---|---|---|
| `serverA-purchase` | serverA | `POST /api/v1/promotions/purchase` | **2/s** | **5** |
| `serverB-orders` | serverB | `/api/v1/orders/**` | 20/s | 50 |
| `serverB-goods-stock` | serverB | `GET /api/v1/goods/*/stock` | 20/s | 50 |
| `serverA` | serverA | `/api/v1/promotions/**`, `/api/v1/goods/**`, `/api/v1/admin/**` | 20/s | 50 |
| `serverC` | serverC | `/api/v1/payments/**` | 20/s | 50 |
| `user-service-auth` | user-service | `POST /login`, `/reissue` | 없음(미적용) | - |
| `user-service` | user-service | `/api/users/**` | 20/s | 50 |
| `cs-bot` | cs-bot | `/api/v1/cs-chat/**` | 20/s | 50 |
| `aiops` | aiops | `/webhook/**`, `/action/**` | 없음(미적용) | - |

구매 엔드포인트만 별도 라우트(`serverA-purchase`)로 분리해 초당 2건으로 강하게 제한 — 선착순 이벤트 어뷰징(매크로 연타) 방어가 목적. 로그인/토큰재발급/AIOps 웹훅은 Rate Limiting이 아예 없다(로그인은 브루트포스 방어가 별도로 없다는 뜻이기도 함 — 이 문서 작성 시점 기준 확인된 사실).

## 4. CORS

`spring.cloud.gateway.globalcors`로 전역 설정(`application.yml`에만 존재). `allowedOrigins`는 `CORS_ALLOWED_ORIGIN` 환경변수(기본값 `http://localhost:3000`) 하나만 지정.

## 5. local vs k8s 라우팅 차이

`application.yml`(local)은 `uri: lb://serverA` 형태로 Eureka 서비스 디스커버리를 사용한다. `application-k8s.yml`은 `k8s` 프로필에서 `http://server-a:8080`처럼 K8s Service DNS로 직접 라우팅한다(`arch-snapshot.md`의 "환경별 프로필 분기" 표와 일치). `JwtAuthFilter`/`RateLimiterConfig`는 프로필과 무관하게 동일하게 동작한다.

> ⚠️ **발견된 불일치**: `application-k8s.yml`의 `spring.cloud.gateway.routes` 목록에는 **cs-bot 라우트(`/api/v1/cs-chat/**`)가 없다** — `application.yml`(local)에만 존재한다. `aiops` 라우트는 오히려 k8s 쪽에 `/slack/**` predicate가 추가되어 있어(Slack Interactive 콜백용) 두 프로필이 단순 상속 관계가 아니라 각각 완전한 라우트 목록을 따로 유지하는 구조임을 보여준다. Spring Boot는 프로필별 YAML의 리스트 프로퍼티(`routes`)를 병합하지 않고 활성 프로필의 목록으로 완전히 교체하므로, 이대로면 **k8s 환경에서 게이트웨이를 통한 cs-bot 접근이 불가능할 수 있다.** 이번 작업 범위(문서화)를 벗어나므로 수정하지 않았고, 실제 k8s 배포 시 확인이 필요하다.
