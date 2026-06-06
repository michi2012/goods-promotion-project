# PR 체크리스트: user-service 통합 + Gateway JWT 검증

- 마지막 업데이트: 2026-06-06

---

## [완료] user-service 이식 통합

- [x] settings.gradle — user-service 추가
- [x] application.yml — application.name / datasource / ddl-auto / jwt 수정
- [x] V1__init_user_schema.sql — users / payment_method / refresh_token DDL
- [x] UserService.java — InvalidPasswordException 교체
- [x] PaymentMethodService.java — resetDefaultPaymentMethod 쿼리 활용
- [x] UserServiceApplication.java — Hooks 제거
- [x] ReissueController.java — ResponseEntity<String>
- [x] build.gradle — spring-security / jjwt 의존성 추가
- [x] Gateway application.yml — /login, /reissue, /api/users/** 라우팅 추가
- [x] docker-compose.yml — mysql-user + user-service 컨테이너 추가
- [x] Helm — user-service 5종 템플릿 + values.yaml
- [x] 테스트 수정 — 6개 실패 테스트 모두 통과
- [x] `./gradlew :user-service:test` ✅
- [x] `helm template ./helm/promotion-app` ✅

---

## [진행 중] Gateway JWT 검증 필터

- [x] 단계 1: build.gradle jjwt 추가
  - [x] `./gradlew :gateway-service:compileJava` ✅
- [x] 단계 2: JwtAuthFilter 구현
  - [x] `./gradlew :gateway-service:compileJava` ✅
- [x] 단계 3: application.yml JWT_SECRET 추가
  - [x] `./gradlew :gateway-service:build` ✅
- [x] 단계 4: JwtAuthFilterTest 작성 (8개 케이스)
  - [x] `./gradlew :gateway-service:test` ✅
- [x] 단계 5: docker compose E2E
  - [x] POST /api/users → 201 (회원가입, 토큰 불필요)
  - [x] POST /login → 200 + Authorization 헤더 토큰 발급
  - [x] 유효 토큰 → GET /api/users/1 → 200
  - [x] 토큰 없이 → 401
  - [x] 위변조 토큰 → 401

## 최종 커밋 전 확인
- [x] E2E 전 시나리오 통과
- [x] `./gradlew :gateway-service:test` 통과
- [x] plan.md 비범위 침범 없음
- [ ] `git diff --stat` 으로 의도하지 않은 변경 없음 확인
