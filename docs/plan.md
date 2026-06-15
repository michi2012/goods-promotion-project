# 계획서: MySQL 조회 도구 추가 (codebot 데이터 조회)

- 작성일: 2026-06-14
- 관련 이슈/티켓: 없음

## 목표
codebot(Worker)에 읽기 전용 자연어→SQL 조회 도구(`DataQueryTools`)를 추가해, Slack에서 PO/기획이 order/payment/user 3개 DB에 대해 자유롭게 데이터를 질문하면 코드블럭 표 형태의 결과와 간단한 분석으로 응답한다.

## 성공 기준
- [ ] `.\gradlew.bat :codebot:build :aiops:build` 통과
- [ ] `DataQueryToolsTest`에서 SELECT-only / 화이트리스트 테이블 / LIMIT 자동 적용 검증 케이스 통과
- [ ] `helm template helm/promotion-app` 렌더링 성공 (`codebot.datasource.order/payment/user.*` 3개 env 반영 확인)
- [ ] 로컬 docker-compose 환경에서 codebot에 자연어 질문(예: "주문 상태별 건수 알려줘")을 보내 SQL 생성 → 실행 → 코드블럭 표 응답을 확인 (E2E)
- [ ] 읽기 전용 계정(`codebot_ro`)으로 PII 컬럼(예: `users.email`) 조회 시 DB 권한 오류로 차단되는지 확인

## 비범위 (Out of Scope)
- "액션"(쓰기) 도구 — 별도 plan으로 분리
- 여러 DB 간 JOIN/통합 조회(order+payment+user 교차) — DB별 단일 스키마 내 조회로 한정
- 쿼리 결과 캐싱/성능 최적화
- 새 `RouteIntent` 카테고리 신설 — 기존 `CODE` 카테고리 설명 확장으로 대체
- Kubernetes Secret으로의 전환 — 기존 `helm upgrade --set` 평문 주입 컨벤션 유지
- 화이트리스트 테이블 확장(orders/goods/payments/users 외 추가 테이블)

## 단계별 작업 계획

### 단계 1: Gradle 의존성 추가 + 3개 DataSource/JdbcTemplate Bean 구성
- 변경 파일:
  - `codebot/build.gradle` — `spring-boot-starter-jdbc`, `com.mysql:mysql-connector-j` 추가 (사용자 확인 완료)
  - `codebot/src/main/java/codebot/codebot/config/DataQuerySourceConfig.java` (신규) — `app.datasource.order/payment/user.*` 커스텀 프로퍼티로 3개 `DataSource` + `JdbcTemplate` Bean을 `@Qualifier`로 구성 (`spring.datasource.*` 자동구성과 분리)
  - `codebot/src/main/resources/application.yaml` — `app.datasource.{order,payment,user}.{url,username,password}` 키 추가 (env var 매핑: `CODEBOT_ORDER_DB_URL` 등)
- 검증: `.\gradlew.bat :codebot:compileJava`
- 롤백: 해당 파일 git checkout
- 예상 소요: 보통
- 리스크: 높음 (새 의존성 — 단계별 승인)

### 단계 2: 읽기 전용 DB 계정 GRANT SQL 스크립트 작성 (3개 DB)
- 변경 파일: `docs/db/codebot-readonly-grants-{order,payment,user}.sql` (신규, DB별로 분리 — 컬럼 단위 GRANT는 대상 테이블이 존재하는 해당 컨테이너에서만 실행 가능하므로 단일 파일로 합치면 다른 DB 대상 GRANT가 실패함)
- 내용: order/payment/user 3개 DB 각각에 `codebot_ro` 계정 생성 + 화이트리스트 테이블의 비-PII 컬럼에 대해서만 `GRANT SELECT (컬럼...)` 부여
  - order DB `orders`: id, order_id, user_id, goods_id, quantity, payment_method, status, created_at, updated_at (제외: shipping_address, zip_code, phone_number, email, delivery_memo, client_ip)
  - order DB `goods`: id, name, stock (전체)
  - payment DB `payments`: id, order_id, user_id, goods_id, quantity, payment_method, status, created_at (제외: shipping_address, zip_code, phone_number, email, delivery_memo, client_ip)
  - user DB `users`: id, user_id, username, role, created_at, updated_at (제외: email, password, phone_number)
- 적용은 사용자가 직접 수행 (`docker exec -i <컨테이너> mysql -u root -proot <db> < codebot-readonly-grants.sql`) — DB 마이그레이션 "파일 생성까지만" 컨벤션과 동일
- 검증: SQL 문법 검토. 실제 적용/계정 동작 확인은 사용자가 수행 후 보고
- 롤백: 계정 `DROP USER` (스크립트에는 포함하지 않음, 필요 시 수동)
- 예상 소요: 보통
- 리스크: 높음 (DB 권한 변경 — 단계별 승인)

### 단계 3: DataQueryTools.java 작성 + 단위 테스트
- 변경 파일:
  - `codebot/src/main/java/codebot/codebot/tools/DataQueryTools.java` (신규)
  - `codebot/src/test/java/codebot/codebot/tools/DataQueryToolsTest.java` (신규)
- 내용: `@Tool(description = "...")`로 3개 DB의 화이트리스트 스키마(테이블/컬럼)를 설명에 포함하고, `executeQuery(database, sql)` 형태로 SELECT문을 입력받아:
  1. SELECT로 시작하는지 검증
  2. 단일 statement인지 검증 (세미콜론 다중 문장 차단)
  3. 화이트리스트 테이블만 참조하는지 검증
  4. LIMIT 없으면 자동 추가 (기본 100)
  5. 해당 DB의 `JdbcTemplate`으로 실행 후 결과를 코드블럭 고정폭 표 텍스트로 변환 (`ObservabilityTools.truncate()` 패턴 재사용)
- 검증: `.\gradlew.bat :codebot:test --tests "*DataQueryToolsTest*"`
- 롤백: 신규 파일 삭제
- 예상 소요: 김
- 리스크: 낮음 (코드 전용, git으로 되돌리기 쉬움 — 단계 4와 묶음 실행 가능)

### 단계 4: CodebotAgentService 통합 + IntentClassifierService 확장
- 변경 파일:
  - `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — `tools(...)`에 `dataQueryTools` 추가, `SYSTEM_PROMPT`에 "데이터 조회" 섹션 추가 (코드블럭 표 + 분석 응답 형식 안내)
  - `aiops/src/main/java/aiops/aiops/router/IntentClassifierService.java` — `CLASSIFY_PROMPT`의 `CODE` 카테고리 설명에 "데이터/통계 조회 요청" 추가
- 검증: `.\gradlew.bat :codebot:compileJava :aiops:compileJava` (+ 기존 `IntentClassifierService` 관련 테스트가 있으면 실행)
- 롤백: 해당 파일 git checkout
- 예상 소요: 짧음
- 리스크: 낮음 (텍스트/연결 변경 — 단계 3과 묶음 실행 가능)

### 단계 5: Helm values/deployment + docker-compose codebot 서비스 신규 추가
- **계획 변경 (단계 진행 중 발견)**: `codebot`은 기존에 `docker-compose.yml`에 서비스 블록/Dockerfile이 전혀 없었음(aiops와 달리 컨테이너화 안 됨). 사용자 확인 결과 "codebot도 docker-compose에 신규 추가"로 결정 — 원래 계획(기존 블록에 env만 추가)보다 범위가 커짐.
- 변경 파일:
  - `helm/promotion-app/values.yaml` — `codebot.datasource.order/payment/user.{url,username,password}` 추가 (serverA/serverC/userService와 동일한 `--set` 패턴)
  - `helm/promotion-app/templates/codebot/deployment.yaml` — 위 값을 env로 주입 (`CODEBOT_{ORDER,PAYMENT,USER}_DB_{URL,USERNAME,PASSWORD}`)
  - `codebot/Dockerfile` (신규) — aiops Dockerfile 기반, kubectl/helm 설치는 제외(KubernetesTools가 kubectl 부재를 이미 허용하므로 이번 범위에서 불필요)
  - `docker-compose.yml` — `codebot` 서비스 블록 신규 추가 (aiops 블록 패턴 참고, mysql/mysql-c/mysql-user에 `depends_on` 추가, `CODEBOT_*_DB_PASSWORD=codebot_ro_pw`)
  - `docker-compose.override.yml` (gitignored, 로컬 전용) — `codebot` 블록에 aiops와 동일한 자격증명/observability URL 재사용
- 검증: `helm template helm/promotion-app`, `docker compose config` (구문 검증)
- 롤백: 해당 파일 git checkout (override는 gitignored이므로 별도)
- 예상 소요: 보통
- 리스크: 높음 (인프라 변경 — 단계별 승인, 사용자 확인 완료)

### 단계 6: 로컬 docker-compose E2E 검증
- 변경 파일: 없음 (검증 전용, 단계 2 SQL 적용 + 단계 5 env 반영 필요)
- 검증: docker-compose로 codebot 재기동 후 자연어 질문 전송 → SQL 생성/실행/응답 확인, PII 컬럼 조회 시도 시 차단 확인
- 예상 소요: 보통
- 리스크: 중간 (단계 2/5 완료 후에만 가능)

### 단계 7: 최종 검증
- `.\gradlew.bat :codebot:build :aiops:build`, `helm template helm/promotion-app`, `git diff --stat`
- `docs/checklist.md` 최종 갱신, 비범위 침범 여부 확인
- 예상 소요: 짧음
- 리스크: 낮음

## 리스크 및 대응
- 리스크 1: LLM이 화이트리스트 외 테이블/PII 컬럼을 참조하는 SQL을 생성할 수 있음 → 대응: (a) 애플리케이션 레벨 화이트리스트/SELECT-only 검증, (b) `codebot_ro` 계정의 컬럼 단위 GRANT로 DB 레벨 이중 방어
- 리스크 2: 3개 `DataSource`를 수동 Bean으로 구성하면서 Spring Boot 기본 `DataSourceAutoConfiguration`과 충돌 가능 → 대응: `spring.datasource.*` 표준 키 대신 커스텀 프리픽스(`app.datasource.*`) 사용, 자동구성은 `@ConditionalOnMissingBean`이라 수동 Bean 존재 시 비활성화됨을 확인
- 리스크 3: 조회 결과가 많을 경우 Slack 메시지 길이/토큰 비용 증가 → 대응: LIMIT 자동 적용(기본 100) + `truncate()` 재사용

## 의존성
- 신규 Gradle 의존성: `spring-boot-starter-jdbc`, `com.mysql:mysql-connector-j` (사용자 확인 완료)
- 읽기 전용 DB 계정(`codebot_ro`) 생성 — 단계 2 SQL을 사용자가 직접 적용
- 기존 컨벤션: `@Tool` 패턴(`ObservabilityTools.java`), Helm `--set` 패턴, DB 마이그레이션 "파일 생성까지만" 패턴

## [진행 중] codebot 전체 기능 E2E 스모크 테스트 + Eureka 기동 순서 수정

- 작성일: 2026-06-14
- 관련 이슈/티켓: 없음

### 목표
codebot의 나머지 기존 기능(CodeSearchTools/ObservabilityTools/LinearTools/PullRequestTools)을 실제 Linear/GitHub API로 E2E 스모크 테스트하고, codebot·aiops 컨테이너의 Eureka(discovery-service) 등록 실패/기동 순서 문제를 해결한다.

### 성공 기준
- [ ] `docker compose config` 통과 (depends_on map 형식 검증)
- [ ] codebot/aiops 컨테이너 로그에 `discovery-service:8761 Connection refused` 경고 없음, discovery-service(`http://localhost:8761`)에 AIOPS/CODEBOT 등록 확인
- [ ] 시나리오 A(단일파일): `POST /internal/investigations`로 CodeSearchTools.java 에러 처리 이슈 조사 → Linear 이슈 생성 → "고쳐서 PR 올려줘" → GitHub PR 생성(단일 파일 수정) 확인
- [ ] 시나리오 B(다중파일): CodeSearchTools.java + ObservabilityTools.java 동일 패턴 이슈 조사 → Linear 이슈 생성 → "고쳐서 PR 올려줘" → createFixPullRequest 미호출, 수정 필요 파일(2개) 안내 응답 확인
- [ ] `PullRequestTools.createFixPullRequest`가 생성하는 PR 본문이 `.github/PULL_REQUEST_TEMPLATE.md`의 5섹션 구조를 따르는지 확인
- [ ] ngrok으로 aiops `/slack/events`를 노출하고 Slack 앱 Event Subscriptions URL 갱신 후, 실제 Slack에서 봇 멘션 → codebot 응답이 Slack 채널에 알림으로 도착하는지 확인
- [ ] 테스트로 생성된 Linear 이슈/GitHub PR/브랜치 정리(Cancel/Close/Delete) 완료

### 비범위 (Out of Scope)
- 이전 plan(MySQL 조회 도구 추가)에서 이미 검증한 DataQueryTools 재테스트
- 실제 운영(k8s/EKS) 환경 배포 검증 — 로컬 docker-compose 범위로 한정
- 코드리뷰 — 사용자 확인에 따라 생략
- codebot이 생성하는 코드 수정 자체의 품질/스타일 검토 (메커니즘 동작 여부 확인이 목적)

### 단계별 작업 계획

#### 단계 1: docker-compose.yml Eureka 기동 순서 수정 (aiops, codebot)
- 변경 파일: `docker-compose.yml`
- 변경 내용: `aiops`(736-742행)와 `codebot`(774-782행)의 `depends_on`을 list 형식 → map 형식으로 전환.
  - 둘 다: `discovery-service` → `condition: service_healthy`, `prometheus`/`loki`/`tempo`/`otel-collector` → `condition: service_started`
  - aiops만: `alertmanager` → `condition: service_started`
  - codebot만: `mysql`/`mysql-c`/`mysql-user` → `condition: service_healthy`
  - (참고 패턴: `server-a`/`server-b`/`server-c`, 200-360행 — healthcheck 있으면 `service_healthy`, 없으면 `service_started`)
- 검증: `docker compose config` (문법/병합 결과 확인, depends_on 항목 정상 출력)
- 롤백: `git checkout -- docker-compose.yml`
- 예상 소요: 짧음
- 리스크: 높음 (인프라 변경 — 단계별 승인)

#### 단계 2: 로컬 docker-compose 기동 + Eureka 등록 확인
- 변경 파일: 없음 (검증 전용)
- 변경 내용: `docker compose up -d --build codebot aiops` (백그라운드 실행) 후 discovery-service(`http://localhost:8761`)에 AIOPS/CODEBOT 등록 확인, `docker compose logs codebot aiops`에서 Eureka 관련 Connection refused 경고 미발생 확인
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 보통 (이미지 빌드 포함)
- 리스크: 중간

#### 단계 3: 시나리오 A — 단일파일 PR 테스트 (CodeSearchTools.java)
- 변경 파일: 없음 (codebot이 실제 GitHub에 PR 생성)
- 변경 내용: `POST http://localhost:8087/internal/investigations`
  - Turn1 (`conversationId=e2e-smoke-scenario-a`): "codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java의 searchCode, getFileContent 메서드를 확인해줘. GitHub API 호출이 실패했을 때(rate limit, 404, 권한 오류 등) catch 블록이 e.getMessage()만 반환해서 실패 원인(HTTP 응답 본문)을 알 수 없는 문제가 있어. 코드를 분석해서 이슈로 등록해줘."
  - Turn2 (동일 conversationId): "고쳐서 PR 올려줘" → 단일 파일 수정 + GitHub PR 생성 기대
- 검증: Turn1 응답에 Linear 이슈 식별자/URL 포함, Turn2 응답에 GitHub PR URL 포함. `mcp__linear__get_issue`/`gh pr view`로 실제 생성 확인
- 롤백: 해당 없음 (정리는 단계 5)
- 예상 소요: 보통
- 리스크: 높음 (실제 Linear/GitHub API 액션 — 단계별 승인)

#### 단계 4: 시나리오 B — 다중파일 "제안" 테스트 (CodeSearchTools.java + ObservabilityTools.java)
- 변경 파일: 없음
- 변경 내용: `POST http://localhost:8087/internal/investigations`
  - Turn1 (`conversationId=e2e-smoke-scenario-b`): "codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java와 codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java를 확인해줘. 두 파일 모두 외부 API(GitHub, Loki/Tempo/Prometheus/Pyroscope) 호출이 실패했을 때 catch (Exception e) 블록에서 e.getMessage()만 반환하고 HTTP 응답 본문을 포함하지 않는 동일한 패턴이 있어. 두 파일을 비교 확인해서 이슈로 등록해줘."
  - Turn2 (동일 conversationId): "고쳐서 PR 올려줘" → 다중 파일(2개) 식별로 PR 생성 거부 + 수정 필요 파일 안내 기대
- 검증: Turn1 응답에 Linear 이슈 식별자/URL 포함, Turn2 응답에 PR 미생성(createFixPullRequest 미호출) 및 2개 파일 안내 포함 확인
- 롤백: 해당 없음 (정리는 단계 5)
- 예상 소요: 보통
- 리스크: 중간 (Linear 이슈는 실제 생성됨 — 단계별 승인)

#### 단계 5: PR 템플릿 준수 — PullRequestTools.createFixPullRequest (완료)
- **계획 변경 (단계 3 진행 중 발견)**: PR #6 본문이 `.github/PULL_REQUEST_TEMPLATE.md`의 5섹션 구조(변경 요약/관련 이슈/테스트 방법/영향도/PR 전 체크리스트)를 따르지 않고 자유 형식으로 생성됨. `prBody` `@ToolParam` description에 템플릿 구조 미반영이 원인.
- 변경 파일: `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java`, `codebot/src/main/java/codebot/codebot/tools/LinearTools.java`
- 변경 내용:
  - `createFixPullRequest`의 `prBody` `@ToolParam` description에 변경 요약/테스트 방법/영향도 및 주의사항 3섹션 구조를 명시 ("관련 이슈"는 자동 추가되므로 제외 안내)
  - `createPullRequest()`에서 "## 관련 이슈\nCloses {issueIdentifier}" + "## PR 전 체크리스트"(정적 텍스트, `PR_CHECKLIST` 상수) + 기존 `PR_NOTICE`를 자동 추가 — LLM 생성 3섹션 + 정적 2섹션으로 템플릿 5섹션 완성
  - **계획 변경 (단계 5 진행 중 발견)**: 재테스트 과정에서 `LinearTools.createIssue`가 `roleLabel`에 "인프라"(직무 라벨)를 `domainLabel` 자리에 선택해 Linear GraphQL "labelIds not exclusive child labels" 오류로 이슈 생성이 2회 연속 실패. `resolveLabelId(labelName, expectedGroup)`로 변경해 라벨의 `parent.name`이 기대 그룹(도메인/직무)과 일치하는지 검증하도록 수정
- 검증: `.\gradlew.bat :codebot:build -x test` 후, 새 conversationId로 시나리오 A와 동일한 단일파일 흐름(Turn1→Turn2)을 재실행하여 생성되는 PR 본문이 템플릿 5섹션 구조를 따르는지 확인 — PR #8에서 5섹션 모두 확인, MIC-13 이슈 생성도 정상 동작 확인
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java codebot/src/main/java/codebot/codebot/tools/LinearTools.java`
- 예상 소요: 짧음
- 리스크: 낮음 (description 텍스트 + 정적 텍스트 추가, 라벨 그룹 검증 추가 — 로직 영향 범위 작음)

#### 단계 6: ngrok 기반 Slack 알림 E2E 테스트
- **계획 변경 (단계 3/4 진행 중 발견)**: `/internal/investigations`는 Slack을 거치지 않아 알림이 발생하지 않음(설계상 정상). 실제 Slack 알림 경로(Slack → aiops `/slack/events` → RouterService → codebot → `chat.postMessage`)는 미검증 상태.
- 변경 파일: 없음 (로컬 인프라 설정 + 외부 Slack App 설정만 변경, 코드 변경 없음)
- 변경 내용:
  1. ngrok으로 aiops 컨테이너의 `/slack/events` 포트를 공개 URL로 노출 (`ngrok http <aiops-port>`)
  2. 사용자가 Slack App 관리 화면(https://api.slack.com/apps)에서 Event Subscriptions Request URL을 ngrok URL(`https://{ngrok-id}.ngrok-free.app/slack/events`)로 갱신 — 외부 서비스 설정이므로 사용자가 직접 수행
  3. 사용자가 Slack 채널에서 봇을 멘션하여 메시지 전송
  4. aiops 로그에서 `app_mention` 이벤트 수신 → RouterService 라우팅 → codebot 응답 → `SlackBotClient.postMessage` 성공(`ok: true`) 확인, Slack 채널에 실제 알림 도착 확인
- 검증: 위 4번 항목
- 롤백: ngrok 프로세스 종료, Slack App Event Subscriptions URL을 이전 값으로 복원(필요 시)
- 예상 소요: 보통 (외부 설정 변경 포함)
- 리스크: 중간 (외부 Slack App 설정 변경 포함 — 단계별 승인, 사용자 작업 필요)

#### 단계 7: 테스트 정리
- 변경 파일: 없음 (Linear/GitHub 리소스 정리)
- 변경 내용: 시나리오 A에서 생성된 GitHub PR Close + 브랜치 삭제, 시나리오 A/B에서 생성된 Linear 이슈 Cancel
- 검증: `gh pr list`, `mcp__linear__list_issues`로 정리 완료 확인
- 롤백: 해당 없음
- 예상 소요: 짧음
- 리스크: 높음 (실제 리소스 종료/삭제 — 단계별 승인)

#### 단계 8: 최종 검증
- 변경 파일: `docs/checklist.md`
- 변경 내용: `git diff --stat` 확인, plan.md "비범위" 침범 여부 확인, 체크리스트 최종 갱신
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 짧음
- 리스크: 낮음

### 리스크 및 대응
- 리스크 1: codebot이 createIssue 시 domainLabel(주문/결제/프로모션/유저)을 codebot 자체 이슈에 딱 맞지 않는 값으로 선택할 수 있음 → 대응: Linear 팀(Michi2012)에 7개 라벨(도메인 4개 + 직무 3개) 모두 존재 확인 완료(`list_issue_labels`). **실제 발생 (단계 5에서 수정)**: LLM이 domainLabel 자리에 "인프라"(직무 라벨)를 선택해 `resolveLabelId`가 라벨은 찾았지만 같은 그룹(직무) 라벨 2개가 제출되어 Linear GraphQL "labelIds not exclusive child labels" 오류로 createIssue 2회 연속 실패. `resolveLabelId(labelName, expectedGroup)`로 부모 그룹 검증 추가하여 해결
- 리스크 2: 시나리오 B에서 codebot이 예상과 달리 2개 파일을 하나의 PR로 묶어 처리하거나, 1개 파일만 골라 PR을 생성할 수 있음 → 대응: 실패로 간주하지 않고 "발견 사항"으로 기록 후 사용자에게 보고 (다중파일 인지/단일파일 인지 분기 자체의 동작 확인이 목적)
- 리스크 3: discovery-service의 `start_period: 30s` 등으로 컨테이너 정상 기동까지 시간이 걸릴 수 있음 → 대응: `docker compose up -d --build`를 `run_in_background: true`로 실행 후 완료 알림 대기
- 리스크 4: 시나리오 A/B에서 codebot의 action 도구(createIssue/createFixPullRequest) 호출이 1차 응답에서는 "진행할까요?" 확인을 요구하는 비결정적 패턴 확인됨 → 대응: "네, 진행해줘" 후속 메시지로 재시도하여 실제 도구 호출 유도 (단계 5 재실행 시에도 동일 패턴 가능성 고려)
- 리스크 5: ngrok 무료 플랜은 URL이 세션마다 바뀌고 동시 터널 수가 제한됨 → 대응: 테스트 1회성이므로 무료 플랜으로 충분, 테스트 종료 후 ngrok 프로세스 종료

### 의존성
- 실제 `GITHUB_TOKEN`, `LINEAR_API_KEY` (docker-compose.override.yml, 기존 설정 재사용)
- 단계 1 완료 후 컨테이너 재기동 필요 (단계 2)
- 단계 3/4는 단계 2(컨테이너 정상 기동) 완료 후에만 가능
- 단계 5는 단계 3에서 발견된 PR 본문 형식 문제에 대한 후속 조치 (코드 변경 후 재빌드 필요)
- 단계 6은 ngrok 설치(로컬에 없으면 설치 필요) 및 Slack App 관리자 권한(Event Subscriptions URL 변경) 필요

## [진행 중] codebot 코드 검색 신뢰성 개선 및 도구 확인 절차 정비 (git-sync 사이드카)

- 작성일: 2026-06-14
- 관련 이슈/티켓: 없음

### 목표
codebot의 (1) GitHub `/search/code` API `total_count: 0` 문제(E2E 스모크 plan 단계 5에서 발견)를 git-sync 사이드카 기반 로컬 검색(`git grep`)으로 근본 해결하고, (2) `createIssue`(조사 완료 후 자동 호출)와 `createFixPullRequest`(실제 코드 변경 전 사용자 확인 필요)의 호출 조건을 SYSTEM_PROMPT에 명시해 LLM의 비결정적 확인 행동(같은 plan 단계 4/5에서 반복 관찰됨)을 정리한다. 두 변경을 ngrok 기반 실제 Slack E2E로 함께 검증한다.

### 성공 기준
- [ ] `.\gradlew.bat :codebot:build` 통과 (`CodeSearchToolsTest` 포함)
- [ ] `helm template helm/promotion-app` 렌더링 성공 (codebot Deployment에 git-sync 사이드카 컨테이너 + emptyDir 볼륨 확인)
- [ ] `docker compose config` 통과 (codebot-git-sync 서비스 + named volume 확인)
- [ ] 로컬 docker-compose E2E: `/internal/investigations`로 실제 키워드(예: "CodeSearchTools") 검색 요청 시 로컬 grep 기반 결과 반환 확인 (기존 `total_count: 0` 문제 재현 안 됨)
- [ ] ngrok Slack E2E: 실제 Slack 메시지로 (a) 코드 검색 정상 동작, (b) createIssue 자동 호출(추가 확인 질문 없이 이슈 생성), (c) createFixPullRequest 호출 전 변경 내용 제시 및 사용자 동의 확인 — 3가지 시나리오 모두 확인
- [ ] `git diff --stat`로 비범위 침범 없음 확인

### 비범위 (Out of Scope)
- `ChatMemory` 외부화(Redis/JDBC) — 별도 작업
- PVC+CronJob 방식 — 검토했으나 미채택 (사이드카+emptyDir로 결정, context.md 참고)
- 기존 "codebot 전체 기능 E2E 스모크 테스트" plan의 단계 6-8 (MIC-10~13 관련 PR 정리 등) — 별개 진행 중인 작업. 이번 plan의 단계 6(ngrok Slack E2E)은 이번 plan에서 변경한 항목(검색 신뢰성, 확인 절차)만 검증하며, ngrok/Slack 설정은 동일하게 재사용 가능하나 시나리오는 별개임
- `getFileContent`의 GitHub API 폴백 — 완전 로컬 대체로 결정(폴백 없음)
- 멀티 레플리카/RWX 스토리지 대응 — codebot.replicas=1 전제
- `createIssue`/`createFixPullRequest` 외 다른 도구의 확인 절차 — 이번 변경 범위는 이슈/PR 생성 두 도구로 한정

### 단계별 작업 계획

#### 단계 1: codebot Dockerfile git 설치 + CodeSearchTools.java 로컬 재작성 + 단위 테스트
- 변경 파일:
  - `codebot/Dockerfile`
  - `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`
  - `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java`
  - `codebot/src/main/resources/application.yaml`
  - `codebot/src/test/resources/application.yaml`
- 변경 내용:
  - `eclipse-temurin:21-jre-jammy`(Debian 기반)에 `apt-get update && apt-get install -y --no-install-recommends git` 추가.
  - `searchCode(query)`: `ProcessBuilder`로 `git -C {repoPath} grep -n -i -e {query}`를 실행(인자는 `List<String>`로 구성). 매치 결과를 `path:line:내용` 형식으로 정리. exit code 1(매치 없음)은 "검색 결과 없음", 그 외 비정상 종료는 에러 메시지 반환.
  - `getFileContent(path)`: `repoPath` 기준으로 경로를 정규화·검증(상위 디렉토리 이탈 차단)한 뒤 `Files.readString`으로 읽고 기존과 동일하게 10,000자 `truncate()` 적용.
  - `githubClient`/`githubOwner`/`githubRepo` 생성자 의존성 제거 (두 메서드 모두 로컬화되어 더 이상 불필요 — orphan cleanup).
  - 신규 설정값: `codebot.repo.local-path` (기본 `/repo/current`), `application.yaml`/`test/application.yaml`에 추가.
- 검증: `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"`
- 롤백: 5개 파일 `git checkout`
- 예상 소요: 보통
- 리스크: 낮음 (코드/테스트/이미지 전용, git으로 되돌리기 쉬움 — 묶음 실행)

#### 단계 2: CodebotAgentService.java — SYSTEM_PROMPT에 도구 호출 확인 절차 명시
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용:
  - "## 이슈 생성 (createIssue)" 섹션에 "사용자에게 생성 여부를 묻지 않고 바로 호출한다"는 규칙 명시 (이슈 생성/수정은 되돌리기 쉬운 낮은 리스크 작업).
  - "## 코드 수정 (createFixPullRequest)" 섹션에 "호출 전 반드시 수정할 파일과 변경 내용 요약을 사용자에게 먼저 제시하고, 명확한 동의(예: '네', '진행해주세요')를 받은 후에만 호출한다"는 규칙 추가. 기존 "여러 파일 수정 필요 시 안내" 규칙은 유지.
- 검증: `.\gradlew.bat :codebot:build` (기존 테스트 영향 없음 확인). 프롬프트 문구 자체의 동작 검증은 단계 6(ngrok Slack E2E)에서 수행.
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 예상 소요: 짧음
- 리스크: 낮음 (프롬프트 텍스트 변경, 되돌리기 쉬움 — 단계 1과 묶음 실행 가능)

#### 단계 3: docker-compose — git-sync 동등 서비스 + named volume 추가
- 변경 파일: `docker-compose.yml`
- 변경 내용:
  - 신규 서비스 `codebot-git-sync` (`registry.k8s.io/git-sync/git-sync` 이미지) 추가 — `GITSYNC_REPO=https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}`, `GITSYNC_REF=main`, `GITSYNC_ROOT=/repo`, `GITSYNC_LINK=current`, `GITSYNC_PERIOD=1m`, 인증은 기존 `GITHUB_TOKEN` 재사용(정확한 env var 이름은 구현 시 git-sync 버전별 문서로 재확인).
  - 신규 named volume `codebot-repo` 추가, `codebot-git-sync`에 `/repo`로 마운트.
  - `codebot` 서비스에 동일 volume(`/repo`, 읽기 전용) 마운트 + `CODEBOT_REPO_PATH=/repo/current` env 추가 + `depends_on: codebot-git-sync (condition: service_started)`.
- 검증: `docker compose config` (서비스/볼륨/env 렌더링 확인)
- 롤백: `git checkout -- docker-compose.yml`
- 예상 소요: 짧음
- 리스크: 높음 (인프라 변경, 새 외부 이미지 의존성 — 단계별 승인)

#### 단계 4: Helm — codebot Deployment에 git-sync 사이드카 + emptyDir 추가
- 변경 파일: `helm/promotion-app/templates/codebot/deployment.yaml`, `helm/promotion-app/values.yaml`
- 변경 내용:
  - `emptyDir` 볼륨 `repo-cache`를 codebot 컨테이너와 신규 `git-sync` 컨테이너에 `/repo`로 공유 마운트.
  - `git-sync` 컨테이너: 단계 3과 동일한 env(`GITSYNC_REPO`, `GITSYNC_REF=main`, `GITSYNC_ROOT=/repo`, `GITSYNC_LINK=current`, `GITSYNC_PERIOD=1m`, 인증 — 기존 `GITHUB_TOKEN` 값 재사용).
  - codebot 컨테이너에 `CODEBOT_REPO_PATH=/repo/current` env 추가.
  - `values.yaml`의 `codebot:` 섹션에 git-sync 이미지/주기 등 필요한 값 추가 (기존 camelCase 키 컨벤션 따름).
- 검증: `helm template helm/promotion-app` (git-sync 컨테이너/볼륨/env 렌더링 확인)
- 롤백: `git checkout -- helm/promotion-app/templates/codebot/deployment.yaml helm/promotion-app/values.yaml`
- 예상 소요: 보통
- 리스크: 높음 (인프라 변경, 새 외부 이미지 의존성 — 단계별 승인)

#### 단계 5: 로컬 docker-compose E2E — git-sync 동기화 + 로컬 검색/확인 절차 1차 확인
- 변경 파일: 없음 (검증 전용)
- 변경 내용: `docker compose up -d --build`(run_in_background) → `codebot-git-sync` 로그에서 clone 성공 확인 → `POST /internal/investigations`로 다음을 확인:
  1. "CodeSearchTools"와 같은 실제 키워드 검색 → 로컬 grep 기반 결과 반환 (E2E 스모크 plan 단계 5에서 발견한 `/search/code` total_count:0 문제가 재현되지 않음을 확인)
  2. (가능하면) "~~ 수정해서 PR 올려줘"류 요청 → 응답이 즉시 PR을 생성하지 않고 변경 내용 확인을 먼저 요청하는지 1차 확인 (정밀 검증은 단계 6)
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 보통 (이미지 빌드 + 최초 git-sync clone 대기 포함)
- 리스크: 중간 (실제 컨테이너 기동/E2E)

#### 단계 6: ngrok Slack E2E — 실제 Slack 메시지로 검색 신뢰성 + 확인 절차 검증
- 변경 파일: 없음 (검증 전용)
- 변경 내용: ngrok으로 codebot(Slack 이벤트 엔드포인트)을 외부에 노출하고 Slack App Event Subscriptions URL을 임시 변경. 실제 Slack 워크스페이스에서 다음을 검증:
  1. 코드 검색 질문(예: "CodeSearchTools가 어떻게 동작해?") → 로컬 git grep 기반 결과가 정상 반환되는지
  2. 조사 흐름에서 별도 요청 없이도 createIssue가 자동 호출되어 Linear 이슈가 생성되는지 (사용자에게 "이슈를 만들까요?" 같은 추가 확인 질문 없이)
  3. "고쳐서 PR 올려줘" 요청 시, createFixPullRequest 호출 전에 변경 내용을 먼저 제시하고 사용자 동의("네"/"진행해주세요")를 받은 뒤에만 호출하는지
- 검증: 위 3가지 시나리오를 실제 Slack 메시지로 전송해 응답/Linear/GitHub 결과 확인
- 롤백: 검증 완료 후 ngrok 종료 + Slack Event Subscriptions URL 원복
- 예상 소요: 보통 (ngrok 설치 필요 시 추가 소요, Slack App 관리자 권한 필요)
- 리스크: 중간 (실제 외부 서비스 연동 — Slack App 설정은 검증 후 원복)

#### 단계 7: 최종 검증
- 변경 파일: `docs/checklist.md`
- 변경 내용: `.\gradlew.bat :codebot:build`(run_in_background), `helm template helm/promotion-app`, `docker compose config` 재확인, `git diff --stat`로 비범위 침범 여부 확인, 체크리스트 최종 갱신.
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 짧음
- 리스크: 낮음

### 리스크 및 대응
- 리스크 1: `registry.k8s.io/git-sync/git-sync` 이미지가 로컬/EKS 환경에서 pull 불가능할 수 있음 → 대응: 단계 3에서 `docker compose pull codebot-git-sync`로 우선 확인, 실패 시 대체 이미지(Docker Hub 미러 등) 검토 후 보고
- 리스크 2: git-sync의 정확한 env var/플래그 이름(버전별 차이)이 기억과 다를 수 있음 → 대응: 단계 3/5에서 컨테이너 로그로 실제 동작 확인 후 단계 3/4 값을 조정 (정상 동작까지 단계 3-5를 반복)
- 리스크 3: `git grep`이 동작하려면 git-sync의 `--link` 심볼릭 링크 대상이 `.git`을 포함한 정상 worktree여야 함 → 대응: 단계 5에서 `docker compose exec codebot git -C /repo/current status`로 확인, 문제 시 `--git-dir`/`--work-tree` 옵션 또는 git-sync 설정 조정
- 리스크 4: git-sync 최초 clone이 끝나기 전에 codebot이 ready 상태가 되어 검색 요청이 들어올 수 있음 → 대응: 이번 plan 범위에서는 "검색 결과 없음"으로 무해하게 처리(기존 `searchCode`도 동일 응답을 반환하던 정상 케이스), 별도 대기 로직 추가하지 않음
- 리스크 5: SYSTEM_PROMPT 문구만으로 LLM의 확인 행동이 100% 결정론적으로 보장되지는 않음 → 대응: 단계 6 ngrok E2E에서 실제 응답으로 확인. 기대와 다르면 프롬프트 문구를 조정해 재검증 (CLAUDE.md 디버깅 루프 탈출 조건 — 동일 이슈 3회 연속 수정 실패 시 중단·보고)
- 리스크 6: ngrok/Slack App 설정 변경은 외부에 노출되는 실제 연동이라 검증 후 원복이 누락되면 보안/운영 리스크 → 대응: 단계 6 종료 시 ngrok 프로세스 종료 + Slack Event Subscriptions URL을 원래 값으로 되돌렸는지 체크리스트로 확인

### 의존성
- 신규 외부 이미지 의존성: `registry.k8s.io/git-sync/git-sync` (Gradle 의존성 아님 — Docker/K8s 이미지)
- 단계 4(Helm) 검증은 `helm template`까지만 — 실제 EKS 적용/검증은 비범위(현재 plan은 로컬 docker-compose + ngrok E2E까지)
- 단계 5는 단계 1-4 모두 완료 후에만 가능, 단계 6은 단계 5 완료 후에만 가능
- 단계 6은 ngrok 설치(로컬에 없으면 설치 필요) 및 Slack App 관리자 권한(Event Subscriptions URL 변경) 필요

## [진행 중] codebot 이슈/PR 생성 UX 개선

- 작성일: 2026-06-15
- 관련 이슈/티켓: 없음 (Slack 시나리오2/3 실사용 중 발견된 UX 개선 — MIC-14는 기존 "코드 검색 신뢰성 개선" plan 단계7 cleanup 대상)

### 목표
ngrok Slack E2E(시나리오2/3) 중 발견된 3가지 UX 문제를 해결한다: (1) createIssue description의 "가설" 템플릿이 코드 개선 요청에는 부적합해 내용 없는 문장만 생성됨, (2) Slack에 표준 마크다운(`**`, `### `, `[text](url)`)이 그대로 노출되어 가독성 저하, (3) createFixPullRequest 호출 전 변경된 파일 전체를 보여줘 변경 부분을 파악하기 어려움.

### 성공 기준
- [ ] `.\gradlew.bat :codebot:build :aiops:build` 통과 (신규/수정 테스트 포함)
- [ ] `SlackBotClientTest`에 마크다운→mrkdwn 변환 테스트(`**`→`*`, `### ` 제거, `[text](url)`→`<url|text>`) 추가 및 통과
- [ ] `CodeSearchToolsTest`에 `previewDiff` 단위 테스트 추가 및 통과
- [ ] `git diff --stat`로 비범위 침범 없음 확인

### 비범위 (Out of Scope)
- 기존 "[진행 중] codebot 코드 검색 신뢰성 개선 및 도구 확인 절차 정비" plan의 단계6 나머지(ngrok 종료/Slack URL 원복), 단계7 — 별도 진행 중인 작업
- 단계7에서 함께 정리하기로 한 MIC-14 이슈/PR cleanup — 기존 plan 범위
- Slack mrkdwn 변환의 모든 마크다운 문법 커버(리스트, 인용, 표 등) — 실제 관찰된 `**bold**`/`### heading`/`[text](url)` 3가지만 처리
- `previewDiff` 결과의 추가 포매팅(코드블럭 언어 힌트 등) — diff 텍스트 생성까지만

### 단계별 작업 계획

#### 단계 1: CodebotAgentService.java — createIssue description 템플릿 분기 (Q1)
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용: "## 이슈 생성 (createIssue)" description 항목을 운영 이상(RCA, "가설" 유지) vs 코드 품질/구조 개선(코드 인용, "가설" 미사용) 두 갈래로 분기하는 문구로 교체.
- 검증: `.\gradlew.bat :codebot:compileJava`
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 예상 소요: 짧음
- 리스크: 낮음 (프롬프트 텍스트만 변경)

#### 단계 2: SlackBotClient.java — 마크다운→mrkdwn 변환 + 테스트 (Q2)
- 변경 파일: `aiops/src/main/java/aiops/aiops/router/SlackBotClient.java`, `aiops/src/test/java/aiops/aiops/router/SlackBotClientTest.java`
- 변경 내용: `postMessage`에서 요청 본문 생성 전 `convertMarkdownToSlackMrkdwn(text)`를 적용 — `**bold**`→`*bold*`, `### heading`→`*heading*`, `[text](url)`→`<url|text>` 정규식 치환. 신규 테스트로 변환 동작(3가지 패턴)을 검증하고, 기존 3개 테스트가 평문 입력에 영향받지 않는지 확인.
- 검증: `.\gradlew.bat :aiops:test --tests "*SlackBotClientTest*"`
- 롤백: `git checkout -- aiops/src/main/java/aiops/aiops/router/SlackBotClient.java aiops/src/test/java/aiops/aiops/router/SlackBotClientTest.java`
- 예상 소요: 짧음
- 리스크: 낮음 (단일 클래스 + 테스트, 되돌리기 쉬움)

#### 단계 3: codebot/build.gradle — java-diff-utils 의존성 추가 (Q3)
- 변경 파일: `codebot/build.gradle`
- 변경 내용: `implementation 'io.github.java-diff-utils:java-diff-utils:{최신 stable 버전}'` 추가 (Maven Central 기준 정확한 버전은 추가 시 확인).
- 검증: `.\gradlew.bat :codebot:build` (의존성 다운로드 + 기존 빌드 통과 확인)
- 롤백: `git checkout -- codebot/build.gradle`
- 예상 소요: 짧음
- 리스크: 높음 (신규 외부 의존성 추가 — 단계별 승인. java-diff-utils 자체는 사전 승인됐으나, 의존성 다운로드/빌드 영향은 이 단계에서 직접 확인)

#### 단계 4: CodeSearchTools.java — previewDiff 도구 추가 + 테스트 (Q3)
- 변경 파일: `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`, `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java`
- 변경 내용: `@Tool previewDiff(String filePath, String newContent)` 추가 — `getFileContent`와 동일한 경로 검증/조회(findByBasename 폴백 포함) 로직으로 기존 내용을 읽고, java-diff-utils의 `DiffUtils.diff` + `UnifiedDiffUtils.generateUnifiedDiff`로 unified diff 문자열을 생성해 `truncate()` 후 반환. 기존 내용 조회 로직은 `getFileContent`와 중복되므로 공용 private 메서드로 추출.
- 검증: `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"`
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java`
- 예상 소요: 보통
- 리스크: 낮음 (신규 메서드 + 테스트, 단계3 완료 후 진행 가능하면 함께 묶음 실행 가능)

#### 단계 5: CodebotAgentService.java — createFixPullRequest 섹션에 previewDiff 안내 추가 (Q3)
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용: "## 코드 수정 (createFixPullRequest)" 섹션에 "newContent 작성 후 previewDiff로 변경 내용을 확인하고, 사용자에게 동의를 구할 때 diff 결과를 함께 보여준다"는 안내 추가.
- 검증: `.\gradlew.bat :codebot:compileJava`
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 예상 소요: 짧음
- 리스크: 낮음 (단계1과 같은 파일이므로 함께 처리, 프롬프트 텍스트만 변경)

#### 단계 6: 최종 검증 및 재배포
- 변경 파일: `docs/checklist.md`
- 변경 내용: `.\gradlew.bat :codebot:build :aiops:build`(run_in_background) 전체 통과 확인 → `docker compose build codebot aiops`(run_in_background) → `docker compose up -d`로 재배포, `git diff --stat`로 비범위 침범 여부 확인, 체크리스트 최종 갱신. Slack 실사용 검증(previewDiff 가독성, mrkdwn 렌더링)은 기존 plan의 ngrok 세션이 열려있으면 같은 세션에서 추가 확인하고, 종료됐다면 다음 ngrok 세션에서 확인하도록 발견 사항에 기록.
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 보통
- 리스크: 중간 (재배포 포함)

### 리스크 및 대응
- 리스크 1: java-diff-utils의 정확한 최신 stable 버전/groupId가 기억과 다를 수 있음 → 대응: 단계3에서 Maven Central 기준 최신 안정 버전 확인 후 추가, 빌드로 검증
- 리스크 2: Slack mrkdwn 변환 정규식이 LLM이 생성하는 다양한 마크다운 변형(중첩 `**`, 코드블럭 내부 `**` 등)을 완전히 커버하지 못할 수 있음 → 대응: 이번에는 실제 관찰된 3가지 패턴만 처리, 추가 패턴은 발견 시 별도 처리
- 리스크 3: previewDiff가 SYSTEM_PROMPT 안내에도 불구하고 LLM이 호출하지 않을 수 있음 (이전 plan에서 관찰된 비결정성과 동일 종류) → 대응: 단계5는 "안내" 수준으로 추가하고, 실사용에서 호출 안 되면 별도 plan으로 프롬프트 강화 검토 (이번 plan은 도구 추가 + 안내까지만, 100% 결정론 보장은 비범위)

### 의존성
- 단계4는 단계3(java-diff-utils 의존성) 완료 후에만 가능
- 단계1/5는 같은 파일(CodebotAgentService.java)이므로 한 번에 함께 수정 가능
- 단계6은 단계1-5 모두 완료 후에만 가능

## [진행 중] codebot 라우팅 안정성 개선 + 이슈 description 코드 인용 강화

- 작성일: 2026-06-15
- 관련 이슈/티켓: 없음 (Slack 시나리오2 ngrok E2E 재검증 중 발견된 이슈)

### 목표
ngrok Slack E2E 재검증 중 발견된 2가지 문제를 해결한다: (1) "생성해"/"진행해"/"너가 판단해" 같은 짧은 후속 메시지가 `IntentClassifierService`에서 UNKNOWN으로 분류되어 `RouterService`가 codebot으로 메시지를 전달하지 않고 고정 안내문("인프라 문제인지 코드/기능 문제인지 알려주세요.")을 반복 반환함, (2) createIssue description의 "코드 품질/구조 개선 요청" 항목에서 코드 인용이 `getFileContent` 실제 내용이 아닌 LLM이 재구성/추측한 코드일 수 있음(같은 E2E에서 PR diff에 존재하지 않는 클래스를 인용한 hallucination 관찰).

### 성공 기준
- [ ] `.\gradlew.bat :aiops:build :codebot:build` 통과 (신규/수정 테스트 포함)
- [ ] `RouterServiceTest`에 sticky 캐시 동작(① INFRA/CODE 분류 시 캐시 갱신, ② UNKNOWN 분류 시 캐시된 직전 라우트 재사용, ③ 캐시 없을 때 기존 안내문 유지) 테스트 추가 및 통과
- [ ] `git diff --stat`로 비범위 침범 없음 확인

### 비범위 (Out of Scope)
- Linear Project/Milestone 자동 연결 — 검토 결과 미적용 결정(현업 triage 표준에 부합, 워크스페이스에 단일 Project "프로모션 시스템 구축"(전체 컨테이너)만 존재해 모든 이슈를 강제 귀속시키는 것이 부적절)
- `IntentClassifierService`의 분류 정확도 자체 개선(프롬프트 튜닝) — sticky 캐시로 우회
- 캐시 TTL/정리(eviction) 로직 — 프로젝트 규모상 생략
- 기존 "코드 검색 신뢰성 개선"/"이슈·PR 생성 UX 개선" plan의 미완료 단계(ngrok 종료/Slack URL 원복, MIC 이슈/PR cleanup 등) — 별개 작업

### 단계별 작업 계획

#### 단계 1: RouterService.java — 스레드별 라우트 sticky 캐시
- 변경 파일: `aiops/src/main/java/aiops/aiops/router/RouterService.java`
- 변경 내용: `ConcurrentHashMap<String, RouteIntent>` 필드(threadTs → 직전 라우트)를 추가한다. `handleAppMention`에서 분류 결과가 INFRA/CODE이면 그대로 라우팅하고 캐시를 갱신한다. UNKNOWN이면 해당 threadTs의 캐시 값이 있으면 그 라우트(INFRA/CODE)로 처리하고, 없으면 기존과 동일하게 `UNKNOWN_GUIDANCE`를 반환한다.
- 검증: `.\gradlew.bat :aiops:test --tests "*RouterServiceTest*"`
- 롤백: `git checkout -- aiops/src/main/java/aiops/aiops/router/RouterService.java`
- 예상 소요: 짧음
- 리스크: 낮음 (단일 클래스, 되돌리기 쉬움)

#### 단계 2: RouterServiceTest.java — sticky 캐시 테스트 추가
- 변경 파일: `aiops/src/test/java/aiops/aiops/router/RouterServiceTest.java`
- 변경 내용: 다음 3가지 케이스를 추가한다. (a) CODE로 캐시된 스레드에서 다음 메시지가 UNKNOWN으로 분류되면 `CodebotClient`로 라우팅됨, (b) 캐시가 없는 스레드에서 UNKNOWN이면 기존과 동일하게 `UNKNOWN_GUIDANCE` 반환, (c) 같은 스레드에서 INFRA → CODE처럼 분류가 바뀌면 캐시가 최신 값으로 갱신됨(주제 전환 대응).
- 검증: `.\gradlew.bat :aiops:test --tests "*RouterServiceTest*"`
- 롤백: `git checkout -- aiops/src/test/java/aiops/aiops/router/RouterServiceTest.java`
- 예상 소요: 보통
- 리스크: 낮음 (테스트 코드만 추가, 단계1과 함께 검증)

#### 단계 3: CodebotAgentService.java — description 코드 인용 출처 강화
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용: "## 이슈 생성 (createIssue)"의 "코드 품질/구조 개선 요청인 경우" 항목에, 인용하는 코드 블록은 `getFileContent`로 조회한 실제 내용을 파일 경로와 함께 그대로 인용해야 하며 추측·재구성한 코드를 작성하지 않는다는 문구를 추가한다.
- 검증: `.\gradlew.bat :codebot:compileJava`
- 롤백: `git checkout -- codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 예상 소요: 짧음
- 리스크: 낮음 (프롬프트 텍스트만 변경)

#### 단계 4: 최종 검증 및 재배포
- 변경 파일: `docs/checklist.md`
- 변경 내용: `.\gradlew.bat :aiops:build :codebot:build`(run_in_background)로 전체 빌드/테스트 통과 확인 → `docker compose build aiops codebot`(run_in_background) → `docker compose up -d aiops codebot` 재배포, "Started" 로그 확인 → `git diff --stat`로 비범위 침범 여부 확인 → 체크리스트 최종 갱신. ngrok 세션이 살아있으면 같은 세션에서 시나리오2 흐름("코드 개선점 분석해서 이슈로 등록해줘" → "진행해"/"생성해" 같은 짧은 후속 메시지가 UNKNOWN 안내문 없이 처리되는지, description 코드 인용이 실제 파일 내용과 일치하는지)을 재검증하고, 종료됐다면 발견 사항에 기록.
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 보통
- 리스크: 중간 (재배포 포함)

### 리스크 및 대응
- 리스크 1: sticky 캐시가 스레드 주제를 잘못 "고정"시킬 수 있음 → 대응: 분류 결과가 INFRA/CODE면 항상 그 결과를 우선 사용하고 캐시를 갱신하므로, 실제로 영향받는 경우는 분류기가 UNKNOWN을 반환할 때뿐이다. 분류기가 명확히 새 주제(INFRA/CODE)를 인식하면 캐시는 즉시 갱신된다.
- 리스크 2: description 코드 인용 강화 문구도 SYSTEM_PROMPT 텍스트라 100% 결정론을 보장하지 않음(기존에 반복 관찰된 LLM 비결정성과 동일 종류) → 대응: 단계4 ngrok E2E에서 실제 인용 내용이 `getFileContent` 결과와 일치하는지 확인하고, 다르면 발견 사항으로 기록(CLAUDE.md 디버깅 루프 탈출 조건 적용 — 3회 연속 수정 실패 시 중단·보고)
- 리스크 3: `ConcurrentHashMap` 캐시는 무한 누적되며 TTL/정리 로직이 없음 → 대응: 이번 plan에서는 비범위로 명시. 운영 중 메모리 이슈가 관찰되면 별도 plan으로 처리

### 의존성
- 단계1과 단계2는 같은 파일 쌍(RouterService.java/RouterServiceTest.java)이라 함께 진행
- 단계3은 단계1/2와 독립적인 모듈(codebot)이라 순서 무관하게 진행 가능
- 단계4는 단계1-3 모두 완료 후에만 가능

## [진행 중] codebot PR 흐름 신뢰성 개선 — diff 인용 + filePath 자동보정

### 목표
ngrok Slack E2E에서 발견된 두 가지 문제를 해결한다: (1) previewDiff가 생성한 diff를 codebot이 응답에서 그대로 인용하지 않고 "위 diff를 확인하라"는 식으로만 안내하는 문제, (2) createFixPullRequest 호출 시 filePath가 이전 턴에서 확인한 경로와 달라져 GitHub API가 404를 반환하는 문제.

### 성공 기준
- [ ] `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"` 통과 — previewDiff 결과가 ```diff 코드 블록으로 감싸지는지 확인
- [ ] `.\gradlew.bat :codebot:test --tests "*PullRequestToolsTest*"` 통과 — filePath 자동보정(basename 일치/불일치/중복) 테스트 포함
- [ ] `.\gradlew.bat :aiops:build :codebot:build` 통과
- [ ] docker compose 재배포 후 "Started CodebotApplication" 로그 확인
- [ ] `git diff --stat`로 의도한 파일만 변경되었는지 확인

### 비범위 (Out of Scope)
- ChatMemory maxMessages 튜닝
- readByBasename/listFiles 공통 컴포넌트 추출 (CodeSearchTools와 PullRequestTools 양쪽에 각자 유지)
- createIssue description 코드 인용 관련 추가 보완 (직전 plan에서 처리됨)
- RouterService sticky 캐시 등 라우팅 관련 추가 작업

### 단계별 작업 계획

#### 단계 1: PullRequestTools.java — filePath 자동보정
- 변경 파일: `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java`
- 변경 내용 요약: `codebot.repo.local-path`(기본값 `/repo/current`) 설정을 받는 `repoPath` 필드/생성자 파라미터를 추가하고, `createFixPullRequest`에서 기존 `isProtectedPath(filePath)` 검사 이후 `resolveFilePath(filePath)`를 호출한다. (a) git 추적 파일 중 정확히 일치하면 그대로 사용, (b) 파일명(basename)만 일치하는 파일이 정확히 1개면 해당 경로로 자동 보정 + `[Tool] 경로 자동 보정: 요청={}, 실제={}` 로그, (c) 일치하는 파일이 없으면 "경로를 찾을 수 없습니다: ..." 오류를 그대로 반환(GitHub API 호출 없음), (d) basename 일치가 2개 이상이면 "여러 개입니다" 오류를 그대로 반환. 보정된 경로에 대해 `isProtectedPath`를 다시 검사한다. `getFileSha`/`commitFile`에는 보정된 경로를 사용한다. `listFiles()`는 CodeSearchTools와 동일한 `git ls-files` 패턴으로 PullRequestTools 내부에 별도 작성한다(공통 추출은 비범위).
  - `@ToolParam filePath` description의 예시 경로(`"aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java"`)는 실제로 존재하는 파일이라, LLM이 컨텍스트 유실로 이 예시를 그대로 anchoring하면 자동보정의 "정확히 일치" 분기를 통과해 의도치 않은 파일을 조용히 커밋할 위험이 있다. 구체적인 예시 경로를 제거하고 "이전 단계(getFileContent/searchCode)에서 확인한 실제 경로를 그대로 사용" 안내로 대체한다.
- 검증 방법: 단계2 테스트로 확인 (빌드는 단계6에서 종합 확인)
- 롤백 방법: git checkout으로 파일 복원
- 예상 소요: 보통

#### 단계 2: PullRequestToolsTest.java — 자동보정 테스트 추가
- 변경 파일: `codebot/src/test/java/codebot/codebot/tools/PullRequestToolsTest.java`
- 변경 내용 요약: `@TempDir Path repoDir`를 추가하고 `@BeforeEach`에서 git init 후 기존 `FILE_PATH` 위치에 파일을 커밋(CodeSearchToolsTest와 동일 패턴)한다. 기존 6개 테스트의 `new PullRequestTools(builder.build(), OWNER, REPO)` 호출을 4-인자(`repoDir.toString()` 추가)로 갱신한다. 신규 테스트 3건 추가:
  - basename만 일치하는 filePath 전달 → FILE_PATH로 자동 보정되어 GitHub API 호출이 FILE_PATH로 이뤄지고 PR 생성 성공
  - 일치하는 파일이 없는 filePath 전달 → "경로를 찾을 수 없습니다" 메시지 반환, GitHub API 호출 없음
  - 동일 basename 파일 2개를 다른 경로에 커밋 → "여러 개입니다" 메시지 반환, GitHub API 호출 없음
- 검증 방법: `.\gradlew.bat :codebot:test --tests "*PullRequestToolsTest*"`
- 롤백 방법: git checkout으로 파일 복원
- 예상 소요: 보통

#### 단계 3: CodeSearchTools.java — previewDiff 결과를 diff 코드 블록으로 감싸기
- 변경 파일: `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`
- 변경 내용 요약: `previewDiff`가 반환하는 `truncate(...)` 결과가 "응답 데이터 없음"이 아닐 때만 ` ```diff\n...\n``` `로 감싸 반환한다. "응답 데이터 없음"이거나 `readFile`의 오류 메시지인 경우는 감싸지 않는다. `getFileContent`/`previewDiff`의 `@ToolParam` 경로 예시(`"aiops/.../AiOpsAgentService.java"`)도 단계1과 동일한 이유로 제거하고 "이전 단계에서 확인한 실제 경로를 그대로 사용" 안내로 대체한다.
- 검증 방법: 단계4 테스트로 확인
- 롤백 방법: git checkout으로 파일 복원
- 예상 소요: 짧음

#### 단계 4: CodeSearchToolsTest.java — 코드 블록 감싸기 검증
- 변경 파일: `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java`
- 변경 내용 요약: `previewDiff_변경있음`에 결과가 ` ```diff\n`로 시작하고 ` \n``` `로 끝나는지 확인하는 assertion을 추가하고, 기존 `@@`/`-class Nested {}`/`+class Nested {` 포함 검증은 유지한다. `previewDiff_변경없음`은 여전히 "응답 데이터 없음"과 정확히 같은지 확인(감싸지지 않음), `previewDiff_파일없음`은 기존 "파일 조회 실패:"로 시작하는지 확인을 유지한다.
- 검증 방법: `.\gradlew.bat :codebot:test --tests "*CodeSearchToolsTest*"`
- 롤백 방법: git checkout으로 파일 복원
- 예상 소요: 짧음

#### 단계 5: CodebotAgentService.java — previewDiff 결과 raw 인용 지시 추가
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용 요약: SYSTEM_PROMPT "## 코드 수정 (createFixPullRequest)" 섹션의 previewDiff 안내 문장 뒤에 "previewDiff가 반환한 내용을 변형하거나 요약하지 않고 그대로 응답에 포함한다"는 문구를 추가한다.
- 검증 방법: `.\gradlew.bat :codebot:compileJava`
- 롤백 방법: git checkout으로 파일 복원
- 예상 소요: 짧음
- 리스크: 낮음 (프롬프트 텍스트만 변경, 단계3의 코드 블록 감싸기가 1차 방어선이고 이 지시는 보조 수단)

#### 단계 6: 최종 검증 및 재배포
- 변경 파일: `docs/checklist.md`
- 변경 내용: `.\gradlew.bat :aiops:build :codebot:build`(run_in_background)로 전체 빌드/테스트 통과 확인 → `docker compose build aiops codebot`(run_in_background) → `docker compose up -d aiops codebot` 재배포, "Started" 로그 확인 → `git diff --stat`로 비범위 침범 여부 확인 → 체크리스트 최종 갱신. ngrok 세션이 살아있으면 시나리오3(코드 수정 → previewDiff 동의 → PR 생성)을 재검증해 (a) diff가 ```diff``` 블록으로 그대로 표시되는지, (b) createFixPullRequest가 404 없이 성공하는지 확인하고, 종료됐다면 발견 사항에 기록한다.
- 검증: 위와 동일
- 롤백: 해당 없음
- 예상 소요: 보통
- 리스크: 중간 (재배포 포함)

### 리스크 및 대응
- 리스크 1: 단계1의 자동보정이 basename 매칭으로 동작하므로, 동일 파일명이 여러 모듈에 존재하면 "여러 개입니다" 오류로 막힐 수 있음 → 대응: 이 경우 LLM이 정확한 경로를 다시 조회하도록 유도하는 것이 의도된 동작이며, 기존 CodeSearchTools.readByBasename과 동일한 트레이드오프
- 리스크 2: 단계5의 raw 인용 지시는 프롬프트 텍스트라 100% 결정론을 보장하지 않음 → 대응: 단계3의 ```diff``` 코드 블록 감싸기가 1차 방어선(코드 레벨)이며, 단계5는 LLM이 그 블록을 누락 없이 응답에 포함하도록 돕는 보조 수단
- 리스크 3: `@ToolParam` 예시 경로 제거가 LLM의 경로 작성 정확도를 오히려 떨어뜨릴 가능성 → 대응: 단계1의 자동보정이 basename 매칭으로 부정확한 경로를 오류로 되돌리므로, 예시 제거로 인한 부정확한 경로는 자동보정 또는 명시적 오류로 처리됨

### 의존성
- 단계1과 단계2는 같은 파일 쌍(PullRequestTools.java/PullRequestToolsTest.java)이라 함께 진행
- 단계3과 단계4는 같은 파일 쌍(CodeSearchTools.java/CodeSearchToolsTest.java)이라 함께 진행, 단계1/2와는 독립적
- 단계5는 단계3 완료 후 진행 (같은 "diff 인용" 목표)
- 단계6은 단계1-5 모두 완료 후에만 가능
