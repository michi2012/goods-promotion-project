# 계획서: E2E 스크립트 고정 + PR CI 개선 (백로그 BL-001)

- 작성일: 2026-11-13
- 관련 이슈/티켓: 백로그 BL-001 (함께 처리: BL-011)
- 브랜치: `feature/e2e-api-and-ci` (main 기준, 승인 후 생성 확인)

## 목표
1. 구매 흐름의 최종 결과(결제 금액·PAID, 주문 상태 PAID)를 확인하는 API E2E와 사전 점검을 저장소 스크립트로 고정하고, UI E2E도 PAID까지 확인하게 한다.
2. PR CI를 "바뀐 것만, 서로 독립적으로, 빠르게" 돌게 하고 프론트 검사를 추가한다.

## 성공 기준
- [ ] serverC `GET /api/v1/payments/{orderId}` 응답에 `amount`·`currency` 포함 (`PaymentServiceTest`, `PaymentRepositoryTest`)
- [ ] 로컬에서 `npm run test:e2e` 실행 시: 사전 점검 통과 → API E2E가 "결제 30,000 KRW PAID + 주문 상태 PAID"를 확인 → UI E2E가 주문 상태 페이지의 PAID까지 확인. 환경이 꺼져 있으면 사전 점검이 명확한 메시지로 실패
- [ ] 프론트 `npm run lint` 에러 0건 (BL-011 해결)
- [ ] PR CI: 백엔드는 모듈별 병렬 job + Gradle 빌드 캐시, 프론트는 lint·Vitest·build job. 문서만 바뀐 PR은 두 워크플로 모두 실행되지 않음. 이 PR에서 두 워크플로 초록색
- [ ] 백엔드 CI에서 쓰지 않는 환경변수(H2 데이터소스, `SPRING_REDIS_HOST`, `NAVER_CLIENT_ID` 등) 제거 후에도 전 모듈 통과

## 비범위 (Out of Scope)
- 전체 스택 E2E CI 워크플로 (BL-007), 배포 후 스모크 (BL-018)
- PR CI에서 mock 기준 UI E2E
- serverA 주문 조회 API 신설
- GitHub 브랜치 보호(필수 체크) 설정

## 단계별 작업 계획

### 단계 1: serverC 결제 조회 응답에 금액 추가
- 변경 파일: `serverC/.../dto/PaymentResponse.java`, `repository/PaymentRepository.java`(조회 SQL·RowMapper), `PaymentServiceTest.java`, `PaymentRepositoryTest.java`
- 변경 내용 요약: `PaymentResponse`에 `amount`(Long, nullable)·`currency` 추가, `findByOrderId`/`findByUserId` SELECT에 포함. 필드 추가라 cs-bot 등 기존 호출에 영향 없음.
- 검증 방법: `.\gradlew.bat :serverC:test --tests PaymentServiceTest --tests PaymentRepositoryTest`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 2: 사전 점검 + 백엔드 E2E (사용자 결정으로 변경 — 아래 "단계 2 변경" 참조)
- 변경 파일: `frontend/e2e/preflight.ts`(신규, globalSetup), `frontend/e2e/api/purchase-flow.spec.ts`(신규), `frontend/playwright.config.ts`, `frontend/package.json`(`test:e2e:api` 스크립트)
- **단계 2 변경 (2026-11-13, 사용자 결정)**: 백엔드팀·프론트팀이 분리돼 있다고 가정해, 백엔드 E2E를 프론트에서 떼어 **독립 폴더 `backend-e2e/`**로 옮긴다. 도구는 Playwright `request`(브라우저·프론트 불필요). 파일: `package.json`·`package-lock.json`·`playwright.config.ts`·`preflight.ts`·`purchase-flow.spec.ts`(단계 ①~④ `test.step`, 응답 첨부, trace) + `SCENARIOS.md`(도구와 무관한 시나리오 명세 — REST Assured 등으로 옮길 때 기준). `frontend/e2e/api/`, `frontend/playwright.api.config.ts`, `test:e2e:api`는 삭제. 프론트 `frontend/e2e/preflight.ts`는 UI E2E용으로 유지(팀 분리 가정상 각 팀이 자기 점검을 소유). REST Assured로 바꾸지 않고 Playwright로 통일한 이유·전환 조건은 `design-notes.md`에 기록
- 변경 내용 요약: 사전 점검은 HTTP만 사용 — serverA·B·C `/actuator/health` UP, Kafka Connect 커넥터 2개·태스크 RUNNING. API E2E는 상품 생성(15,000원) → 구매 2개(실행마다 고유 userId) → serverC 결제를 폴링해 `{status: PAID, amount: 30000, currency: KRW}` → serverB 주문 상태 PAID. 주소·타임아웃은 환경변수(기본값 로컬).
- 검증 방법: 로컬 환경 기동 상태에서 `npm run test:e2e:api` 통과 / 환경 일부를 끈 상태에서 사전 점검 실패 메시지 확인
- 롤백 방법: 신규 파일 삭제, `git checkout --` 수정 파일
- 예상 소요: 보통

### 단계 3: UI E2E가 PAID까지 확인
- 변경 파일: `frontend/e2e/purchase.spec.ts`
- 변경 내용 요약: 구매 후 주문 상태 페이지에서 새로고침하며 PAID 표시를 폴링(Server Component라 화면이 자동 갱신되지 않음).
- 검증 방법: `npm run test:e2e` (UI E2E 포함 전체)
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 4: 기존 lint 에러 정리 (BL-011)
- 변경 파일: `frontend/src/app/orders/[orderId]/page.tsx`
- 변경 내용 요약: try/catch 안 JSX 3건을 구매 페이지와 같은 방식(네트워크 호출만 try, JSX는 밖)으로 정리. 동작 변화 없음.
- 검증 방법: `npm run lint` 0건, `npm run test`, `npx tsc --noEmit`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 5: PR CI 개선 ⚠️ 인프라(CI) 변경 — 단계별 승인
- 변경 파일: `.github/workflows/backend-ci.yml`(신규), `.github/workflows/frontend-ci.yml`(신규), `.github/workflows/ci-cd.yaml`(`test` job 제거)
- 변경 내용 요약:
  - backend-ci: 백엔드 경로(모듈 소스, `*.gradle`, `gradle/**`, `gradlew*`, 자신)가 바뀐 PR + main push에서 실행. 모듈별 matrix(`fail-fast: false`), `gradle/actions/setup-gradle`, `./gradlew :<모듈>:test --build-cache`. `clean` 제거, 미사용 환경변수 제거. main push 실행으로 캐시 저장(PR은 읽기).
  - frontend-ci: `frontend/**`, `contracts/**`가 바뀐 PR에서 `npm ci` → lint → Vitest → build.
  - ci-cd.yaml: PR `test` job 제거(배포 파이프라인은 그대로).
- 검증 방법: YAML 파싱, 이 PR의 Actions 결과(두 워크플로 초록색, 모듈별 job 병렬). 캐시 효과는 main 머지 후 다음 PR에서 관찰
- 롤백 방법: 신규 워크플로 삭제, `git checkout -- .github/workflows/ci-cd.yaml`
- 예상 소요: 보통

### 단계 6: 문서·백로그
- 변경 파일: `.claude/skills/e2e/project.md`(사전 점검 = HTTP 점검, PR CI 행 수정, 실제 경로), `CLAUDE.md`("테스트 분업 원칙"의 "프론트는 CI 없음" 전제 수정), `docs/backlog.md`(BL-000 완료 #24, BL-001·BL-011 진행 → 완료)
- 검증 방법: 육안 확인
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 7: 로컬 E2E 최종 검증 (근거 보고)
- 변경 파일: 없음
- 변경 내용 요약: 새 규칙대로 사전 점검 → API·UI E2E 실행, 결과·스크린샷·데이터 전후를 보고하고 사용자 확인 후 커밋.
- 검증 방법: `npm run test:e2e`, 핵심 화면 스크린샷, serverC/serverB 응답
- 롤백 방법: 해당 없음
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: 환경변수 제거로 어떤 모듈 테스트가 깨짐 → 대응: 사전 조사상 미사용(user-service는 `@TestPropertySource`로 자체 지정). 깨지면 해당 변수만 복원
- 리스크: setup-gradle 캐시는 기본 브랜치에서만 저장 → 대응: backend-ci를 main push에도 실행해 캐시 시드
- 리스크: 모듈별 job마다 JDK·Gradle 준비 시간이 들어 총 사용 시간(분)은 늘 수 있음 → 대응: 체감 대기 시간(가장 느린 모듈)과 격리를 우선. 실측 후 조정
- 리스크: 경로 필터로 워크플로가 안 돌면 향후 "필수 체크"를 걸 때 머지가 막힘 → 대응: 지금은 필수 체크 미설정. 설정 시 skip 처리 방식 함께 결정 (발견 사항으로 기록)
- 리스크: UI E2E의 PAID 대기가 CDC·결제 지연으로 느려짐 → 대응: 타임아웃 넉넉히(30~60초), 사전 점검으로 커넥터 상태 선확인

## 의존성
- 로컬 E2E: Docker로 MySQL 2·Redis 2·Kafka·Kafka Connect·Eureka·serverA/B/C 기동
- GitHub Actions: `actions/setup-node`, `gradle/actions/setup-gradle`(Gradle 공식) — 서드파티 아님
- 신규 npm·Gradle 의존성 없음
