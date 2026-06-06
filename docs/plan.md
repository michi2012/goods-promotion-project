# PR 계획서: user-service 통합 + Gateway JWT 검증

- 브랜치: main (feature 브랜치 분리 필요 시 별도 확인)

---

## [완료] user-service 이식 통합

- 작성일: 2026-06-06

### 목표
다른 프로젝트에서 가져온 user-service를 이 프로젝트에 완전히 통합한다.
원본 서비스(serverC) 설정 오염 제거, 코드 버그 수정, Gateway 라우팅, Docker Compose, Helm 차트까지 포함한다.

### 성공 기준
- [x] `./gradlew :user-service:build` 통과
- [x] `docker compose config` 오류 없음 (mysql-user + user-service 컨테이너 포함)
- [x] `helm template ./helm/promotion-app` 오류 없음 (user-service 리소스 포함)
- [x] Gateway 라우팅 `/api/users/**`, `/login`, `/reissue` → user-service 경로 추가 확인
- [x] Flyway migration SQL이 users / payment_method / refresh_token 3개 테이블을 모두 생성

### 비범위
- Gateway JWT 검증 필터 구현 → 아래 섹션에서 처리
- `/internal/api/**` 인증 미들웨어 (라우팅 미노출로 대체)
- mysql-user Debezium CDC 연동

### 변경 파일
- `settings.gradle`, `user-service/build.gradle`
- `user-service/src/main/resources/application.yml`, `application-k8s.yml`
- `user-service/src/main/resources/db/migration/V1__init_user_schema.sql` (신규)
- `user-service/src/main/java/.../UserService.java`, `PaymentMethodService.java`, `UserServiceApplication.java`, `ReissueController.java`
- `gateway-service/src/main/resources/application.yml`, `application-k8s.yml`
- `docker-compose.yml`
- `helm/promotion-app/values.yaml`, `templates/user-service/*` (5개 신규)

---

## [진행 중] Gateway JWT 검증 필터

- 작성일: 2026-06-06

### 목표
Gateway에 GlobalFilter로 JWT 검증을 추가하여 인증이 필요한 모든 라우트를 보호한다.
검증 성공 시 X-User-Id / X-User-Role 헤더를 downstream에 전달하고,
docker compose 기반 E2E로 로그인~보호 경로 접근까지 확인 후 커밋한다.

### 성공 기준
- [ ] `./gradlew :gateway-service:test` 통과 (JwtAuthFilter 단위 테스트 포함)
- [ ] 토큰 없이 `/api/users/**` 호출 → 401 (docker compose + curl 확인)
- [ ] 유효한 토큰으로 `/api/users/**` 호출 → 200 (docker compose + curl 확인)
- [ ] `POST /login`, `POST /reissue` 는 토큰 없이 통과
- [ ] `./gradlew :gateway-service:build` 통과

### 비범위
- Role 기반 경로 접근 제어 (인증 여부만 검사)
- Refresh 토큰 만료 검증 (reissue는 user-service가 처리)
- Helm 차트 gateway deployment에 JWT_SECRET 추가 (별도 커밋)

### 단계별 계획

#### 단계 1: gateway-service build.gradle에 jjwt 의존성 추가
- 변경 파일: `gateway-service/build.gradle`
- 검증: `./gradlew :gateway-service:compileJava`

#### 단계 2: JwtAuthFilter 구현
- 변경 파일: `gateway-service/src/main/java/.../filter/JwtAuthFilter.java` (신규)
- GlobalFilter + Ordered (order=-1), 화이트리스트: POST /login, POST /reissue
- 성공 시 X-User-Id, X-User-Role 헤더 추가, 실패 시 401
- 검증: `./gradlew :gateway-service:compileJava`

#### 단계 3: gateway application.yml JWT_SECRET 추가
- 변경 파일: `application.yml`, `application-k8s.yml`
- 검증: `./gradlew :gateway-service:build`

#### 단계 4: JwtAuthFilter 단위 테스트 작성
- 변경 파일: `gateway-service/src/test/.../filter/JwtAuthFilterTest.java` (신규)
- 케이스: 화이트리스트 통과 / 토큰 없음 401 / 위변조 401 / 유효 토큰 통과 + 헤더 확인
- 검증: `./gradlew :gateway-service:test`

#### 단계 5: docker compose E2E 검증 (사용자 직접 실행)
- POST /login → 200 + access token
- 유효 토큰 → GET /api/users/{id} → 200
- 토큰 없이 → 401, 위변조 토큰 → 401

### 리스크
- JWT claim 키 이름: user-service JWTUtil 실제 claim 키를 단계 2 진입 시 확인
- Spring WebFlux: GlobalFilter는 Mono<Void> 반환, 블로킹 코드 금지
