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

- 마지막 업데이트: 2026-06-15

### 진행 상황
- [x] 단계 1: codebot Dockerfile git 설치 + CodeSearchTools.java 로컬 재작성 + 단위 테스트
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` — BUILD SUCCESSFUL)
- [x] 단계 2: CodebotAgentService.java — SYSTEM_PROMPT 확인 절차 규칙 추가
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build` — BUILD SUCCESSFUL)
- [x] 단계 3: docker-compose — git-sync 서비스 + named volume 추가
  - [x] 검증 통과 (`docker compose config --quiet` — exit 0, codebot-git-sync/codebot-repo/CODEBOT_REPO_PATH 렌더링 확인)
  - [x] `docker compose pull codebot-git-sync` 이미지 pull 확인 (`registry.k8s.io/git-sync/git-sync:v4.2.4` Pulled 성공)
- [x] 단계 4: Helm — codebot Deployment에 git-sync 사이드카 + emptyDir 추가
  - [x] 검증 통과 (`helm template helm/promotion-app -s templates/codebot/deployment.yaml` — git-sync 컨테이너/repo-cache emptyDir/CODEBOT_REPO_PATH 렌더링 확인)
- [x] 단계 5: 로컬 docker-compose E2E — git-sync 동기화 + 로컬 검색/확인 절차 1차 확인
  - [x] git-sync clone 성공 로그 확인 (`"msg":"updated successfully","ref":"main","remote":"b4dadf0b..."`, `/repo/current` 심볼릭 링크로 마운트 확인)
  - [x] `/internal/investigations`에서 실제 키워드 검색 → 로컬 grep 결과 반환 확인 ("CodeSearchTools" 검색 → 정의/사용처/테스트 3개 파일 정확히 반환, 기존 `total_count:0` 버그 해결 확인)
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
- (단계 1+2 검증 중 발견·해결) `:codebot:build` 실행 시 `LinearToolsTest.createIssue_성공`이 실패함 — 이번 plan과 무관한 기존 회귀(커밋 `9a79fe4`에서 `LinearTools.resolveLabelId()`에 `parent.name` 그룹 검증을 추가했으나, `LinearToolsTest.java`의 `issueLabels` mock 응답에 `parent` 필드가 누락되어 항상 그룹 불일치로 실패). `createIssue_성공`과 `createIssue_success_false` 두 테스트의 mock 응답에 `"parent": {"name": "도메인"/"직무"}`를 추가하여 해결, 재실행 통과.
- (단계 1 plan 문구 보완·해결) `codebot/src/test/resources/application.yaml`에 `codebot.repo.local-path: /repo/current` 추가 — `CodeSearchToolsTest`는 `new CodeSearchTools(repoDir.toString())`로 Spring 컨텍스트 없이 직접 생성하고 `@Value`에 기본값(`/repo/current`)이 있어 기능적 영향은 없으나, plan 문구(`application.yaml`/`test/application.yaml` 모두 추가)와 일치시킴.
- (단계 5 진행 중 발견·해결) `docker-compose.yml`의 `GITSYNC_PASSWORD: ${GITHUB_TOKEN}`이 이 로컬 환경에 `.env` 파일이 없어 빈 문자열로 치환되어 git-sync가 `required flag: $GITSYNC_PASSWORD ... must be specified when --username is specified`로 즉시 종료(Exited 1)함. 기존 `codebot`/`aiops` 서비스가 시크릿을 주입받는 것과 동일한 방식(gitignore된 `docker-compose.override.yml`의 `environment:` 블록이 base의 빈 `${VAR}` 치환을 키 단위로 덮어씀)으로, `codebot-git-sync` 서비스에 `GITSYNC_REPO`(owner/repo 리터럴)/`GITSYNC_PASSWORD`를 추가하여 해결.
- (단계 5 진행 중 발견·해결) 위 수정 후 git-sync가 `Run(git init -b git-sync): ... "/repo/.git: Permission denied"`로 재시도 실패함. git-sync v4 이미지는 기본 non-root(uid/gid 65533)로 실행되는데, 새로 생성된 Docker named volume(`codebot-repo`)은 `root:root 0755`라 쓰기 권한이 없음 — Kubernetes `emptyDir`도 기본 root 소유라 단계4 Helm 사이드카도 동일 문제. `docker-compose.yml`의 `codebot-git-sync`에 `user: "0:0"` 추가, `helm/promotion-app/templates/codebot/deployment.yaml`의 `git-sync` 컨테이너에 `securityContext.runAsUser: 0` 추가로 해결. 재기동 후 클론 성공(`"msg":"updated successfully"`).
- (단계 6 진행 중 발견·수정) 실제 Slack 시나리오1("CodeSearchTools가 어떻게 동작해?")은 정상 응답했으나, 같은 스레드의 시나리오2("방금 본 CodeSearchTools.java 코드에서 개선점 분석해서 이슈로 등록해줘")에서 봇이 "CodeSearchTools.java라는 이름의 파일을 찾을 수 없습니다"를 반환함. 원인 분석: `searchCode("CodeSearchTools.java")`(확장자 포함)는 `git grep`이 파일 **내용**을 검색하는데, 실제 소스 파일(`codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`)은 자기 파일명을 문자열로 포함하지 않아 매칭되지 않고 `docs/checklist.md`/`context.md`/`plan.md`에서 "CodeSearchTools.java"를 언급한 라인만 매칭됨 → LLM이 실제 경로를 모른 채 `getFileContent("CodeSearchTools.java")`를 바로 호출해 `/repo/current/CodeSearchTools.java` 없음으로 실패. ChatMemory/conversationId 연속성 문제 아님(같은 threadTs → 같은 conversationId 확인). 수정: `CodeSearchTools.getFileContent`에서 경로로 파일을 못 찾으면 `git ls-files`로 베이스네임이 일치하는 파일을 찾는 `findByBasename` 폴백 추가 — 단일 매치 시 해당 파일 내용 반환, 복수 매치 시 후보 경로 목록 반환. `CodeSearchToolsTest`에 단일/다중 매치 테스트 2건 추가, `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` BUILD SUCCESSFUL(7개 전체 통과). codebot 이미지 재빌드(`bootJar` 포함)/재기동 후 `/internal/investigations` 정상 응답 확인. 시나리오2 Slack 재검증은 다음 단계에서 수행 필요.
- (단계 6 진행 중 발견·수정, 2차) 위 `findByBasename` 폴백 적용·배포 후에도 시나리오2가 동일 증상("CodeSearchTools.java라는 이름의 파일을 레포지토리에서 찾을 수 없습니다")으로 재차 실패. codebot 로그 확인 결과 `getFileContent`는 호출되지 않았고 `searchCode("CodeSearchTools.java")`만 실행되어 `docs/*` 언급 매치만 반환됨 → LLM이 실제 소스 파일 경로를 인지하지 못한 채 `getFileContent` 호출 없이 종료(폴백 자체는 정상이나 호출 경로를 타지 않음). 수정: `searchCode`에 `findPathMatches`(쿼리 문자열을 `git ls-files` 결과의 경로와 대소문자 무시 substring 매칭) 추가, 매치가 있으면 결과에 "파일 경로 일치:" 섹션을 덧붙임(매치 없으면 기존 포맷 그대로 유지, `searchCode_결과없음` 테스트 영향 없음). `findByBasename`과 `findPathMatches`가 공유하는 `listFiles()`(`git ls-files`) 헬퍼로 리팩터링. 신규 테스트 `searchCode_파일경로일치` 추가, `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` BUILD SUCCESSFUL(8개 전체 통과). `bootJar` → `docker compose build codebot` → `docker compose up -d codebot` 재배포, "Started CodebotApplication" 로그 확인. `docker compose exec codebot git -C /repo/current ls-files | grep -i CodeSearchTools`로 `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` 경로가 정상 노출됨을 확인(side-effect 없는 검증). 시나리오2 Slack 재검증(3차 시도) 필요.
- (단계 6 진행 중 발견 — 미수정, 별개 이슈) codebot 재기동 후 `org.springframework.jdbc.CannotGetJdbcConnectionException` / `Access denied for user 'codebot_ro'@'172.25.0.8'` 에러가 주기적으로 반복됨(order/payment/user 3개 datasource 모두). 이번 plan(코드 검색 신뢰성)과 무관한 `executeQuery`/`DataQueryTools`용 DB 계정 권한 이슈로 추정(컨테이너 재기동으로 IP가 바뀌면서 `codebot_ro` 사용자의 호스트 제한 그랜트와 불일치 가능성). `/internal/investigations` 자체 응답에는 영향 없음(코드 검색 관련 질의는 DB 접근 불필요). 별도 확인 필요.

## [진행 중] codebot 이슈/PR 생성 UX 개선

- 마지막 업데이트: 2026-06-15

### 진행 상황
- [x] 단계 1: CodebotAgentService.java — createIssue description 템플릿 분기 (Q1)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:compileJava` — BUILD SUCCESSFUL)
- [x] 단계 2: SlackBotClient.java — 마크다운→mrkdwn 변환 + 테스트 (Q2)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test --tests "*SlackBotClientTest*"` — BUILD SUCCESSFUL, 신규 변환 테스트 3건 포함)
- [x] 단계 3: codebot/build.gradle — java-diff-utils 의존성 추가 (Q3)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build` — BUILD SUCCESSFUL, `io.github.java-diff-utils:java-diff-utils:4.12` 의존성 해석 및 기존 테스트 통과 확인)
- [x] 단계 4: CodeSearchTools.java — previewDiff 도구 추가 + 테스트 (Q3)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` — BUILD SUCCESSFUL, 기존 8개 + 신규 3개 = 11개 통과. getFileContent/findByBasename을 readFile/readByBasename(FileLookup)로 공용 추출)
- [x] 단계 5: CodebotAgentService.java — createFixPullRequest 섹션에 previewDiff 안내 추가 (Q3)
  - [x] 검증 통과 (단계4 테스트 실행 시 compileJava 포함 — BUILD SUCCESSFUL)
- [x] 단계 6: 최종 검증 및 재배포
  - [x] `.\gradlew.bat :codebot:build :aiops:build` 통과 (BUILD SUCCESSFUL, codebot:test 8개+신규3개=11개 포함)
  - [x] `docker compose build codebot aiops` 통과 (promotion-codebot/promotion-aiops 이미지 재생성)
  - [x] `docker compose up -d` 재배포 — codebot/aiops Recreated → Started, "Started CodebotApplication" 로그 확인
  - [x] `git diff --stat` 변경 범위 확인 — 이번 plan 대상 파일(아래) 모두 포함, 비대상 파일은 기존 "코드 검색 신뢰성 개선" plan의 미커밋 변경(별개)

### 최종 검증
- [x] `.\gradlew.bat :codebot:build :aiops:build` 통과
- [x] `SlackBotClientTest` 신규 변환 테스트 통과 (단계2에서 확인)
- [x] `CodeSearchToolsTest` previewDiff 테스트 통과 (단계4에서 확인)
- [x] plan.md "비범위" 침범 여부 확인 — 침범 없음 (ngrok/MIC-14 cleanup/전체 마크다운 커버리지/추가 diff 포맷팅 모두 미작업)
- [x] `git diff --stat`로 의도하지 않은 파일 변경 없는지 확인 — 이번 plan 대상: `aiops/.../SlackBotClient.java`, `aiops/.../SlackBotClientTest.java`, `codebot/build.gradle`, `codebot/.../CodebotAgentService.java`, `codebot/.../CodeSearchTools.java`, `codebot/.../CodeSearchToolsTest.java`, `docs/plan.md`, `docs/context.md`, `docs/checklist.md` 모두 변경됨. 그 외 변경 파일(`codebot/Dockerfile`, `codebot/src/main/resources/application.yaml`, `codebot/src/test/resources/application.yaml`, `codebot/.../LinearToolsTest.java`, `docker-compose.yml`, `helm/...`)은 이번 plan 시작 전부터 있던 "코드 검색 신뢰성 개선" plan의 미커밋 변경이며 이번 plan에서 추가로 손대지 않음

### 발견 사항 (작업 중 별도 처리 필요한 것)
- (이번 plan과 별개, 기존 plan에서 이어짐) MIC-14 이슈/PR(시나리오2/3에서 생성, PR은 "네" 미전송으로 미생성) — 기존 "코드 검색 신뢰성 개선" plan 단계7 cleanup 대상에 MIC-10/11/12/13과 함께 추가 필요
- Slack 실사용 검증(previewDiff 가독성, mrkdwn 렌더링 — `**`/`###`/링크가 실제 Slack 메시지에서 올바르게 표시되는지)은 이번 세션에서 ngrok 세션이 종료되어 미수행. 다음 ngrok 기반 Slack E2E 세션(기존 "코드 검색 신뢰성 개선" plan 단계6)에서 시나리오2(코드 개선 이슈 등록 — description 템플릿 분기 확인)/시나리오3(PR 생성 동의 — previewDiff 확인)와 함께 재검증 필요
- (단계6 이후, ngrok Slack E2E 재검증 중 발견·수정) 시나리오2("CodeSearchTools.java 코드에서 개선하면 좋을 점이 있으면 분석해서 이슈로 등록해줘")에서 1차 응답은 분석 후 "이슈를 생성하겠습니다"로 서술만 하고 createIssue 미호출(기존 비결정성 패턴과 동일). 이어 사용자가 "진행해"라고 보내자 봇이 "인프라 문제인지 코드/기능 문제인지 알려주세요"라는 질문을 반환했고, "너가 판단해야지"에도 동일 질문을 반복하며 멈춤(createIssue 미호출). 원인 추정: 이번 plan 단계1(Q1)에서 추가한 description 분기("운영 이상(장애/에러/성능 저하 등) 조사" vs "코드 품질/구조 개선 요청")가 LLM에게 새로운 판단 포인트로 인식되어, 이 판단을 사용자에게 되묻는 패턴을 유발한 것으로 보임("인프라 문제"≈"운영 이상", "코드/기능 문제"≈"코드 품질 개선"과 대응). 수정: SYSTEM_PROMPT 해당 분기 첫 문장에 "아래 두 유형 중 어느 쪽인지는 사용자의 요청 내용을 바탕으로 직접 판단하며, 어떤 유형인지 사용자에게 묻지 않는다"를 추가. `.\gradlew.bat :codebot:build`(BUILD SUCCESSFUL) → `docker compose build codebot` → `docker compose up -d codebot` 재배포, "Started CodebotApplication" 로그 확인. Slack 재검증(시나리오2 createIssue 자동 호출, 분기 질문 미발생 확인) 필요.

## [진행 중] codebot 라우팅 안정성 개선 + 이슈 description 코드 인용 강화

- 마지막 업데이트: 2026-06-15

### 진행 상황
- [x] 단계 1: RouterService.java — 스레드별 라우트 sticky 캐시
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test --tests "*RouterServiceTest*"` — BUILD SUCCESSFUL)
- [x] 단계 2: RouterServiceTest.java — sticky 캐시 테스트 추가
  - [x] 검증 통과 (단계1과 동일 명령, 신규 테스트 2건(캐시 재사용/캐시 갱신) 포함 전체 통과)
- [x] 단계 3: CodebotAgentService.java — description 코드 인용 출처 강화
  - [x] 검증 통과 (`.\gradlew.bat :codebot:compileJava` — BUILD SUCCESSFUL)
- [x] 단계 4: 최종 검증 및 재배포
  - [x] `.\gradlew.bat :aiops:build :codebot:build` 통과 (BUILD SUCCESSFUL)
  - [x] `docker compose build aiops codebot` 통과 (promotion-aiops/promotion-codebot 이미지 재생성)
  - [x] `docker compose up -d aiops codebot` 재배포 — Recreated → Started, "Started CodebotApplication"/"Started AiopsApplication" 로그 확인
  - [x] `git diff --stat` 변경 범위 확인 — 이번 plan 대상 파일(RouterService.java, RouterServiceTest.java, CodebotAgentService.java, docs/plan.md, docs/context.md, docs/checklist.md) 모두 포함, 그 외 변경은 기존 "코드 검색 신뢰성 개선"/"이슈·PR 생성 UX 개선" plan의 미커밋 변경(별개)

### 최종 검증
- [x] `.\gradlew.bat :aiops:build :codebot:build` 통과
- [x] `RouterServiceTest` 신규 sticky 캐시 테스트(① 캐시 재사용 ② 캐시 없음 ③ 캐시 갱신) 통과 — ②는 기존 `handleAppMention_UNKNOWN` 테스트로 커버됨
- [x] plan.md "비범위" 침범 여부 확인 — 침범 없음 (Linear Project/Milestone, 분류기 프롬프트, 캐시 TTL 모두 미작업)
- [x] `git diff --stat`로 의도하지 않은 파일 변경 없는지 확인

### 발견 사항 (작업 중 별도 처리 필요한 것)
- (정정) 이전 "[진행 중] codebot 이슈/PR 생성 UX 개선" 섹션의 발견 사항(단계6 ngrok 재검증, "인프라 문제인지 코드/기능 문제인지" 반복 응답의 원인을 단계1(Q1) description 분기로 추정)은 이후 조사에서 부정확함이 확인됨 — 실제 원인은 `RouterService.UNKNOWN_GUIDANCE`(짧은 후속 메시지가 IntentClassifier에서 UNKNOWN으로 분류되어 codebot에 도달하지 못함, 위 단계1 참고). 해당 항목은 historical record로 유지하고 별도 수정하지 않음.
- Linear Project/Milestone 자동 연결 미적용은 계획 단계에서 검토 후 비범위로 확정(현업 triage 표준 + 단일 Project "프로모션 시스템 구축"에 강제 귀속 부적합) — 코드 변경 불필요
- ngrok Slack E2E 재검증(짧은 후속 메시지가 UNKNOWN 안내문 없이 처리되는지, description 코드 인용이 실제 파일 내용과 일치하는지)은 이번 세션에서 ngrok 세션이 없어 미수행. 다음 ngrok 기반 Slack E2E 세션에서 시나리오2(코드 개선 이슈 등록 → "진행해"/"생성해" 후속 메시지 → PR 생성 동의)와 함께 재검증 필요

## [진행 중] codebot PR 흐름 신뢰성 개선 — diff 인용 + filePath 자동보정

- 마지막 업데이트: 2026-06-15

### 진행 상황
- [x] 단계 1: PullRequestTools.java — filePath 자동보정
  - [x] 검증 통과 (단계2 테스트로 확인)
- [x] 단계 2: PullRequestToolsTest.java — 자동보정 테스트 추가
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*PullRequestToolsTest*"` — 9/9 통과)
- [x] 단계 3: CodeSearchTools.java — previewDiff 코드 블록 감싸기 + @ToolParam 예시 제거
  - [x] 검증 통과 (단계4 테스트로 확인)
- [x] 단계 4: CodeSearchToolsTest.java — 코드 블록 감싸기 검증
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` — 11/11 통과)
- [x] 단계 5: CodebotAgentService.java — previewDiff 결과 raw 인용 지시 추가
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build` 컴파일 성공)
- [x] 단계 6: 최종 검증 및 재배포
  - [x] `.\gradlew.bat :codebot:build` 통과 (BUILD SUCCESSFUL)
  - [x] `docker compose build aiops codebot` 통과
  - [x] `docker compose up -d aiops codebot` 재배포 — "Started CodebotApplication"/"Started AiopsApplication" 로그 확인
  - [x] `git diff --stat` 변경 범위 확인

### 최종 검증
- [x] `.\gradlew.bat :codebot:build` 통과
- [x] PullRequestToolsTest 신규 자동보정 테스트(basename 일치/없음/중복) 통과
- [x] CodeSearchToolsTest previewDiff 코드 블록 감싸기 테스트 통과
- [x] plan.md "비범위" 침범 여부 확인 — 이번 plan 변경 파일(PullRequestTools/CodeSearchTools/CodebotAgentService 및 테스트)은 비범위 항목(ChatMemory 튜닝, 공통 컴포넌트 추출, createIssue 템플릿, RouterService 캐시)을 침범하지 않음
- [x] `git diff --stat`로 의도하지 않은 파일 변경 없는지 확인 — 이번 plan 대상 5개 파일 외 변경분은 기존 미커밋 plan("코드 검색 신뢰성 개선"/"이슈·PR UX 개선")의 잔여 변경으로, 이번 작업에서 추가로 손대지 않음
- [x] ngrok 세션 확인 — `https://pushup-defender-bloated.ngrok-free.dev` (localhost:8085) 활성 상태. Slack 시나리오3(코드 수정 → previewDiff 동의 → PR 생성) 재검증은 사용자가 직접 수행 필요
- [x] Slack 시나리오3 재검증 결과(사용자 확인): diff가 ```diff``` 블록으로 그대로 표시됨, `createFixPullRequest`가 404 없이 성공(PR #9 생성). diff 인용/filePath 자동보정 모두 정상 동작 확인

### 발견 사항 (작업 중 별도 처리 필요한 것)
- ngrok Slack E2E 재검증(시나리오3: diff가 ```diff``` 블록으로 그대로 표시되는지, createFixPullRequest가 404 없이 성공하는지)은 ngrok 세션 상태에 따라 단계6에서 수행 여부가 달라짐 — 세션이 살아있으면 즉시 검증, 종료됐으면 다음 세션에서 재검증 필요
