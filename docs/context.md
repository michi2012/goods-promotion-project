# 맥락 노트: E2E 스크립트 고정 + PR CI 개선 (BL-001)

## 왜 이 방식을 선택했는가
결제 금액 작업(PR #22) 검증 회고에서 정한 규칙(`.claude/skills/e2e`)을 실제 스크립트와 CI로 구현한다.
- 서버 간 흐름 검증을 매번 즉석 명령으로 했고, UI E2E는 화면 이동만 확인해 CDC 고장을 놓쳤다.
- PR CI는 문서만 바꿔도 9개 모듈을 `clean test`로 순차 실행(약 6분)했고, 한 모듈 실패가 뒤 모듈 실행을 막아 serverC·user-service 테스트가 한동안 CI에서 돌지 않았다.

사용자 결정 (2026-11-13):
- **금액 확인은 serverC 결제 조회 응답에 `amount`·`currency`를 추가**해 API로 확인(블랙박스). serverA에는 주문 조회 API가 없고, DB 직접 조회는 원칙 위반 + 새 npm 의존성.
- **CI 선택 실행은 워크플로 분리(백엔드/프론트, GitHub 기본 `paths`) + Gradle 빌드 캐시.** 외부 action 없이, 모듈 간 의존 관계는 Gradle 입력 해시로 판단(루트 빌드 설정이 바뀌면 자동으로 전체 재실행). 모듈별 matrix로 격리.
- **PR CI의 프론트는 lint·Vitest·build까지.** UI·API E2E는 로컬 + 이후 BL-007.

추가 판단:
- 사전 점검은 HTTP만(`/actuator/health`, Kafka Connect REST). Node에서 `docker` CLI를 부르지 않아 셸·OS 독립. health 응답에 DB·Redis 상태가 포함되어 인프라도 간접 확인.
- 프론트 CI에 lint를 넣으면 기존 lint 에러(BL-011, orders 페이지 3건)로 즉시 실패하므로 이번 범위로 가져온다.
- 백엔드 CI 환경변수: user-service는 `@TestPropertySource`로 H2를 직접 지정, codebot은 `spring.datasource` 미사용, `NAVER_CLIENT_ID`·`SPRING_REDIS_HOST`는 설정 파일 어디에도 없음 → 제거.

## 검토했으나 채택하지 않은 대안
### 대안 A: `dorny/paths-filter`로 바뀐 모듈만 matrix 구성
- 왜 안 썼나: 서드파티 action, 공통 설정 변경 시 전체 실행 규칙을 직접 관리해야 함. Gradle 캐시가 의존 관계를 정확히 판단.

### 대안 B: PR CI에서 Prism mock 기준 UI E2E
- 왜 안 썼나: 상품 생성·구매 흐름을 mock으로 바꿔야 해 범위가 크게 늘어남.

### 대안 C: 사전 점검에서 `docker ps`로 컨테이너 확인
- 왜 안 썼나: 셸·Docker CLI 의존. health·커넥터 상태가 실제로 필요한 조건을 더 직접 확인.

## 기존 코드베이스 컨벤션
- Playwright: `frontend/playwright.config.ts`(testDir `./e2e`, webServer `npm run dev`), 기존 스펙 `frontend/e2e/purchase.spec.ts`, `order-status.spec.ts`
- serverC 조회: `PaymentRepository`(JdbcTemplate + RowMapper), `PaymentResponse` record
- lint 정리 패턴: `frontend/src/app/purchase/[goodsId]/page.tsx`(네트워크 호출만 try, JSX는 밖)
- CI: `.github/workflows/ci-cd.yaml`(PR `test` job + 머지 시 배포 파이프라인, `DEPLOY_ENABLED`)

## 관련 파일/위치
- `.claude/skills/e2e/SKILL.md`, `project.md` — E2E 원칙과 적용 값
- `serverC/src/main/java/promotion/serverC/dto/PaymentResponse.java`, `repository/PaymentRepository.java`
- `frontend/e2e/` — E2E 스펙
- `.github/workflows/` — CI

## 외부 참조
- Playwright `globalSetup`, `request` fixture, `expect.poll`
- `gradle/actions/setup-gradle` (Gradle 공식 action, 기본 브랜치에서만 캐시 저장)
