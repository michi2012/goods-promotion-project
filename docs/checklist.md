# 체크리스트: MySQL 조회 도구 추가 (codebot 데이터 조회)

- 마지막 업데이트: 2026-06-14

## 진행 상황
- [x] 단계 1: Gradle 의존성 추가 + 3개 DataSource/JdbcTemplate Bean 구성
  - [x] 검증 통과 (`.\gradlew.bat :codebot:compileJava` — BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 2: 읽기 전용 DB 계정 GRANT SQL 스크립트 작성 (3개 DB, 파일 분리)
  - [x] SQL 문법 검토 통과 (작성 완료, 실제 적용/동작 확인은 단계 6에서 사용자가 수행)
  - [ ] 코드리뷰 통과
- [x] 단계 3: DataQueryTools.java 작성 + 단위 테스트
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*DataQueryToolsTest*"` — BUILD SUCCESSFUL, 8개 테스트)
  - [ ] 코드리뷰 통과
- [x] 단계 4: CodebotAgentService 통합 + IntentClassifierService 확장
  - [x] 검증 통과 (`.\gradlew.bat :codebot:compileJava :aiops:compileJava` — BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 5: Helm values/deployment + docker-compose codebot 서비스 신규 추가
  - [x] 검증 통과 (`helm template helm/promotion-app` — CODEBOT_*_DB_* 9개 env 렌더링 확인, `docker compose config --services` — codebot 서비스 포함 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 로컬 docker-compose E2E 검증
  - [x] 자연어 질문 → SQL 생성/실행/코드블럭 표 응답 확인 ("주문 상태별 건수 알려줘" → PAID 1건 표로 응답)
  - [x] PII 컬럼(예: users.email) 조회 시도 시 DB 권한 오류로 차단 확인 (ERROR 1143 SELECT command denied for column 'email')
  - [x] (단계 진행 중 발견) `orders` 테이블 PII 컬럼으로 인한 `SELECT *` 차단 → LLM이 hallucination 응답 반환 → `@Tool` description에 `SELECT *` 금지 규칙 추가로 해결, 재테스트 통과
- [x] 단계 7: 최종 검증

## 최종 검증
- [x] `.\gradlew.bat :codebot:build :aiops:build` 통과 (BUILD SUCCESSFUL in 1m 9s)
- [x] `helm template helm/promotion-app` 렌더링 성공 (`codebot.datasource.order/payment/user.*` 9개 env 반영 확인)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 — 침범 없음
- [x] 의도하지 않은 파일 변경이 없는지 `git diff`로 최종 확인 — `git status --porcelain` 결과 모두 이번 plan 범위 내 파일

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (단계 7에서 발견·해결) `:codebot:test` 전체 실행 시 `CodebotApplicationTests.contextLoads()`가 `PlaceholderResolutionException: Could not resolve placeholder 'data-query.datasource.order.url'`로 실패 — 단계 1에서 추가한 `@Value("${data-query.datasource.*.url}")`(기본값 없음)에 대해 `src/test/resources/application.yaml`에 테스트용 값이 누락됨. `observability`/`github`/`linear`와 동일한 패턴으로 `data-query.datasource.{order,payment,user}.*` 테스트값 추가하여 해결, 재실행 통과.

## [진행 중] codebot 전체 기능 E2E 스모크 테스트 + Eureka 기동 순서 수정

- 마지막 업데이트: 2026-06-14 (작성)

### 진행 상황
- [x] 단계 1: docker-compose.yml Eureka depends_on 수정 (aiops, codebot)
  - [x] 검증 통과 (`docker compose config` — aiops 6개/codebot 8개 depends_on map 형식 정상 변환)
- [x] 단계 2: 로컬 docker-compose 기동 + Eureka 등록 확인
  - [x] 검증 통과 (`/eureka/apps`에 CODEBOT/AIOPS UP 등록 확인, 로그에 discovery-service 8761 Connection refused 경고 없음. aiops의 localhost:8080 kubectl 오류는 기존 별개 이슈)
- [x] 단계 3: 시나리오 A — 단일파일 PR 테스트 (CodeSearchTools.java)
  - [x] Turn1: Linear 이슈 생성 확인 (MIC-10 — CodeSearchTools의 GitHub API 에러 핸들링 개선 필요)
  - [x] Turn2: GitHub PR 생성 확인 (PR #6 — feature/mic-10-codebot-fix, CodeSearchTools.java 단일 파일 +9/-0, "Closes MIC-10" 포함)
- [x] 단계 4: 시나리오 B — 다중파일 제안 테스트 (CodeSearchTools.java + ObservabilityTools.java)
  - [x] Turn1: Linear 이슈 생성 확인 (MIC-11 — 외부 API 호출 실패 시 에러 응답 본문 누락 개선, conversationId=e2e-smoke-scenario-b-2)
  - [x] Turn2: PR 미생성 확인 (createFixPullRequest 미호출, 로그 확인) — 응답에 두 파일(CodeSearchTools.java/ObservabilityTools.java) 모두 언급, "한 파일씩 순차 PR 생성"을 제안하며 사용자 확인 대기 상태로 종료
- [x] 단계 5: PR 템플릿 준수 (PullRequestTools.createFixPullRequest prBody description 수정)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build -x test` — BUILD SUCCESSFUL, compileJava 포함)
  - [x] 재배포 후 새 conversationId로 단일파일 흐름 재실행, PR 본문 5섹션 구조 확인 (PR #8 — 변경 요약/테스트 방법/영향도 및 주의사항/관련 이슈(Closes MIC-13)/PR 전 체크리스트 모두 포함)
- [ ] 단계 6: ngrok 기반 Slack 알림 E2E 테스트
  - [ ] ngrok으로 aiops `/slack/events` 노출
  - [ ] Slack App Event Subscriptions URL 갱신 (사용자 작업)
  - [ ] Slack 멘션 → codebot 응답 → Slack 알림 도착 확인
- [ ] 단계 7: 테스트 정리 (GitHub PR Close/브랜치 삭제, Linear 이슈 Cancel)
  - 대상: MIC-10/PR #6(feature/mic-10-codebot-fix), MIC-11, MIC-12/PR #7(feature/mic-12-codebot-fix), MIC-13/PR #8(feature/mic-13-codebot-fix)
- [ ] 단계 8: 최종 검증

### 최종 검증
- [ ] `docker compose config` 통과
- [ ] discovery-service에 codebot/aiops 등록 확인
- [ ] 시나리오 A/B 응답 결과 기록
- [x] PR #8 본문이 템플릿 5섹션 구조를 따르는지 확인 (PR #6은 템플릿 보강 전 생성되어 이전 형식 유지, 정리 시 함께 처리)
- [ ] ngrok 기반 Slack 알림 E2E 확인
- [ ] 생성된 Linear 이슈/GitHub PR/브랜치 정리 완료
- [ ] plan.md "비범위" 침범 여부 확인
- [ ] `git diff --stat`로 의도하지 않은 파일 변경 없는지 확인

### 발견 사항 (작업 중 별도 처리 필요한 것)
- (단계 3 Turn1 진행 중 발견·해결) `LinearTools.resolveLabelId()`의 GraphQL 쿼리에서 `$teamId: String!` → `$teamId: ID!`로 타입 오류 수정 (Linear API 스키마 요구사항). 1차 수정 후에도 동일 오류가 재발했는데, 원인은 `docker compose up -d --build`의 `COPY build/libs/*SNAPSHOT.jar` 레이어가 CACHED되어 수정 전 JAR이 그대로 배포된 것 — `.\gradlew.bat :codebot:build -x test` 후 재배포하여 해결, Turn1 재시도 성공 (MIC-10 생성).
- (단계 3 Turn2 진행 중 발견 — 단계 5에서 해결) codebot의 `PullRequestTools.createFixPullRequest`는 `.github/PULL_REQUEST_TEMPLATE.md`(5섹션 구조)를 사용하지 않고 자유 형식 PR 본문을 생성함 (PR #6 확인). `prBody` `@ToolParam` 설명에 변경 요약/테스트 방법/영향도 및 주의사항 3섹션 구조를 명시하고, `createPullRequest()`에서 "## 관련 이슈"/"## PR 전 체크리스트" 섹션을 정적 텍스트로 자동 추가하도록 수정. PR #8에서 5섹션 모두 포함 확인.
- (단계 5 진행 중 발견·해결) `LinearTools.createIssue`가 `roleLabel`로 "인프라"(직무 라벨)를 `domainLabel` 자리에 잘못 선택하는 등, `resolveLabelId()`가 라벨의 부모 그룹(도메인/직무)을 검증하지 않아 Linear GraphQL "labelIds not exclusive child labels" 오류로 이슈 생성이 2회 연속 실패함(conversationId=e2e-smoke-step5-2). `resolveLabelId(labelName, expectedGroup)`로 변경해 `parent.name`이 기대 그룹과 일치하는지 검증하고, 불일치 시 올바른 후보 목록을 포함한 에러 메시지를 반환하도록 수정. MIC-13 생성으로 정상 동작 재확인.
- (단계 5 진행 중 발견 — 미수정, 외부 이슈) GitHub `/search/code` API가 이 PUBLIC 레포의 실재하는 파일(`CodeSearchTools.java`, `CodebotApplication.java` 등)에 대해 `total_count: 0`을 반환함 (`gh api search/code` 직접 호출로 재현, Contents API로는 파일 존재 확인됨, rate limit도 30/30 여유). codebot 코드 문제가 아닌 GitHub 코드 검색 인덱싱/외부 이슈로 추정. 우회: codebot에게 정확한 파일 경로를 알려주면 `getFileContent`(Contents API)로 정상 동작.
- (단계 4 Turn1, 단계 5 Turn1 반복 관찰) codebot이 `createIssue`/`createFixPullRequest` 호출 전에 "진행할까요?"로 서술만 하고 실제 도구를 호출하지 않는 패턴이 재현됨(conversationId=e2e-smoke-step5-2, e2e-smoke-step5-3). "네, 진행해줘" 후속 메시지로 실제 호출됨. 시스템 프롬프트에 확인 절차 지시 없음 — LLM 비결정적 동작으로 기존 발견 사항과 동일한 패턴.
- (단계 3/4 진행 중 발견 — 미수정) `/internal/investigations`는 Slack 경로를 거치지 않아 Slack 알림이 발생하지 않음(설계상 정상). 실제 Slack 알림 E2E는 별도로 aiops를 ngrok 등으로 외부에 노출하고 Slack App Event Subscriptions URL을 갱신해야 함 — 현재 미구성.
- (단계 4 Turn1 진행 중 발견 — 코드 수정 없음) 시나리오 B(2파일 조사)에서 codebot이 `createIssue`를 즉시 호출하지 않고, 1차 응답에서는 "이슈 등록 완료"라고 서술하지만 실제로는 호출되지 않은 채 "[미정]" placeholder를 반환(conversationId=e2e-smoke-scenario-b, 재현 확인 후 폐기). 새 conversationId(e2e-smoke-scenario-b-2)로 재시도 시 1차 응답은 "이슈 생성을 진행할까요?"로 확인을 요청했고, "네, 진행해줘" 후속 메시지에서 비로소 `createIssue` 호출됨(MIC-11). 시나리오 A(1파일)에서는 동일 패턴 요청에서 확인 절차 없이 바로 호출됨 — 시스템 프롬프트에 확인 절차 지시는 없으므로 LLM의 비결정적 동작으로 추정.
- (단계 4 Turn2 진행 중 발견 — 코드 수정 없음) 시스템 프롬프트는 "여러 파일 수정 필요 시 createFixPullRequest를 호출하지 말고 안내"를 지시하지만, 실제 응답은 "두 파일 모두 수정이 필요하므로 CodeSearchTools.java부터 먼저 PR 생성"을 제안하며 다시 사용자 확인을 요청(미호출, PR 미생성으로 종료). plan.md 리스크2에서 예견된 "1개 파일만 골라 PR 생성 시도" 케이스에 해당 — 다만 확인 대기 상태에서 멈춰 실제 PR은 생성되지 않음.

## [진행 중] codebot 코드 검색 신뢰성 개선 및 도구 확인 절차 정비 (git-sync 사이드카)

- 마지막 업데이트: 2026-06-14 (작성)

### 진행 상황
- [ ] 단계 1: codebot Dockerfile git 설치 + CodeSearchTools.java 로컬 재작성 + 단위 테스트
  - [ ] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"`)
- [ ] 단계 2: CodebotAgentService.java — SYSTEM_PROMPT 확인 절차 규칙 추가
  - [ ] 검증 통과 (`.\gradlew.bat :codebot:build`)
- [ ] 단계 3: docker-compose — git-sync 서비스 + named volume 추가
  - [ ] 검증 통과 (`docker compose config`)
- [ ] 단계 4: Helm — codebot Deployment에 git-sync 사이드카 + emptyDir 추가
  - [ ] 검증 통과 (`helm template helm/promotion-app`)
- [ ] 단계 5: 로컬 docker-compose E2E — git-sync 동기화 + 로컬 검색/확인 절차 1차 확인
  - [ ] git-sync clone 성공 로그 확인
  - [ ] `/internal/investigations`에서 실제 키워드 검색 → 로컬 grep 결과 반환 확인
- [ ] 단계 6: ngrok Slack E2E — 실제 Slack 메시지로 검색/확인 절차 검증
  - [ ] 코드 검색 시나리오 확인
  - [ ] createIssue 자동 호출(추가 확인 질문 없음) 확인
  - [ ] createFixPullRequest 호출 전 사용자 동의 확인 절차 확인
  - [ ] ngrok 종료 + Slack Event Subscriptions URL 원복
- [ ] 단계 7: 최종 검증

### 최종 검증
- [ ] `.\gradlew.bat :codebot:build` 통과
- [ ] `helm template helm/promotion-app` 렌더링 성공 (git-sync 사이드카/볼륨/env 확인)
- [ ] `docker compose config` 통과 (codebot-git-sync/volume 확인)
- [ ] plan.md "비범위" 침범 여부 확인
- [ ] `git diff --stat`로 의도하지 않은 파일 변경 없는지 확인

### 발견 사항 (작업 중 별도 처리 필요한 것)
- (이 plan으로 해결 예정) "[진행 중] codebot 전체 기능 E2E 스모크 테스트 + Eureka 기동 순서 수정" 섹션의 "발견 사항" 중 "GitHub `/search/code` API total_count:0" 항목 — 이 plan 완료 후 해당 항목을 "해결됨"으로 갱신 필요
- (이 plan으로 해결 예정) 같은 섹션의 "발견 사항" 중 createIssue/createFixPullRequest 확인 절차 비결정성 항목(단계 4 Turn1, 단계 5 Turn1 등) — 이번 plan 단계 2(SYSTEM_PROMPT 확인 절차 명시) + 단계 6(ngrok E2E)으로 결정론적 동작 확인 후 "해결됨"으로 갱신 필요
