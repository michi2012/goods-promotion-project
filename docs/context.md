# 맥락 노트: MySQL 조회 도구 추가 (codebot 데이터 조회)

## 왜 이 방식을 선택했는가

"사례5: 데이터 에이전트 구축" 케이스(PO가 Slack에서 자유롭게 데이터를 질문하면 마크다운 표 + 분석으로 응답)를 codebot에 적용하고 싶다는 요청에서 출발했다. "액션"(쓰기) 도구는 리스크가 커서 이번 작업은 "조회"(읽기 전용)만 다룬다. 사용자는 대상 DB를 order/payment/user 3개 전체로, NL→SQL 방식·DB 자격증명·응답 형식·라우팅은 모두 추천안으로 진행하기로 했다.

- **NL→SQL: 하이브리드(화이트리스트 + SELECT-only + LIMIT + 컬럼 단위 GRANT)** — 자유 SQL 생성으로 사례5의 "자유 질문" 취지를 살리되, 화이트리스트 테이블/SELECT-only/단일 statement/LIMIT 검증을 애플리케이션에서 강제한다. PII(이메일/연락처/주소/비밀번호 등)는 애플리케이션 검증만으로는 우회 가능성이 있으므로, `codebot_ro` 계정 자체에 컬럼 단위 `GRANT SELECT`를 적용해 DB 레벨에서 원천 차단한다 — 방어 계층 이중화.
- **응답 형식: 코드블럭(```) 고정폭 표** — Slack mrkdwn은 GFM 표(`| --- |`)를 지원하지 않으므로, 코드블럭으로 감싼 고정폭 텍스트 표 + 표 아래 간단한 분석 문장으로 응답한다.
- **라우팅: 새 RouteIntent 미신설, 기존 CODE 카테고리 확장** — 데이터 조회는 codebot의 기존 `investigate()` 흐름(다중 `@Tool` 단일 에이전트)에 자연스럽게 편입 가능하므로, `IntentClassifierService`의 `CLASSIFY_PROMPT` 중 `CODE` 카테고리 설명에 "데이터/통계 조회 요청"을 추가하는 것으로 충분하다. `RouterService`/`CodebotClient` 변경 불필요.
- **DB 자격증명: 새 읽기 전용 계정 + Helm `--set` 패턴** — 기존 serverA/serverC/userService의 `datasource.{url,username,password}` 컨벤션을 그대로 따른다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 순수 자유 SQL (화이트리스트 없음)
- 무엇: LLM이 생성한 SQL을 SELECT 검증만 거쳐 그대로 실행
- 왜 안 썼나: PII 컬럼/예상치 못한 테이블(예: `flyway_schema_history`, 향후 추가될 outbox류 테이블 등) 노출 위험을 통제할 수 없다.

### 대안 B: 사전 정의 쿼리 템플릿
- 무엇: "주문 상태별 건수", "재고 부족 상품 Top N" 등 미리 정의된 쿼리 + 파라미터 바인딩
- 왜 안 썼나: 새 질문 유형마다 코드 추가가 필요해 사례5의 "자유롭게 질문" 취지와 거리가 멀다.

### 대안 C: 새 RouteIntent("DATA_QUERY") 신설
- 무엇: `IntentClassifierService`에 새 카테고리를 추가하고 `RouterService`에 새 분기를 추가
- 왜 안 썼나: codebot의 `investigate()`는 이미 여러 `@Tool` 클래스를 한 에이전트에 묶어 LLM이 스스로 도구를 선택하는 구조다. 데이터 조회 도구를 이 목록에 추가하기만 하면 되므로, 새 분기/새 클라이언트 메서드는 불필요한 변경 범위 확대다.

## 기존 코드베이스 컨벤션
- `@Tool` 패턴: `codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java` — `@Slf4j @Component`, 생성자 주입(`@Qualifier`), `@Tool(description = """...""")` + `@ToolParam`, try/catch 후 설명적 에러 문자열 반환, `truncate()` 헬퍼(10,000자 제한)
- 멀티 도구 에이전트: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — 단일 `ChatClient`에 `.tools(...)`로 여러 `@Tool` 클래스를 등록, LLM이 `@Tool description`을 보고 호출 여부 결정
- Helm 민감값 주입: `helm/promotion-app/values.yaml` 헤더 주석 — `helm upgrade --set <service>.datasource.password=...` 패턴 (serverA/serverC/userService 참고)
- DB 마이그레이션: SQL 파일 생성까지만 하고 실제 적용은 사용자가 수행 (`/db-migration` 컨벤션) — 읽기 전용 계정 생성 GRANT SQL도 동일 패턴 적용

## 관련 파일/위치
- `codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java` — 새 `DataQueryTools`의 참고 패턴
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — `SYSTEM_PROMPT` 및 `tools()` 통합 지점
- `aiops/src/main/java/aiops/aiops/router/IntentClassifierService.java` — `CLASSIFY_PROMPT` 확장 지점
- `codebot/build.gradle` — 신규 의존성(`spring-boot-starter-jdbc`, `mysql-connector-j`) 추가 지점
- `codebot/src/main/resources/application.yaml` — `app.datasource.{order,payment,user}.*` 설정 키 추가 지점
- `helm/promotion-app/values.yaml`, `helm/promotion-app/templates/codebot/deployment.yaml` — Helm 환경변수 주입
- `docker-compose.yml` (mysql/mysql-c/mysql-user, codebot 서비스 블록) — 로컬 DB 연결 정보 및 codebot env 추가 지점
- `serverA/.../entity/Order.java`, `Goods.java`, `serverC/.../entity/Payment.java`, `user-service/.../entity/User.java` — 화이트리스트 스키마(`orders`, `goods`, `payments`, `users`) 출처

## 외부 참조
- 없음

## [진행 중] codebot 전체 기능 E2E 스모크 테스트 + Eureka 기동 순서 수정

### 왜 이 방식을 선택했는가
이전 plan(MySQL 조회 도구 추가)에서 DataQueryTools는 검증했지만, codebot의 나머지 도구(CodeSearchTools, ObservabilityTools, LinearTools, PullRequestTools, KubernetesTools)는 실제 Linear/GitHub API로 동작 확인이 안 된 상태였다. 사용자는 "실제 코드봇이 깃허브 api로 단일파일수정일경우 수정하고 pr 생성, 다중파일일경우 제안 이런식으로 실제 되는지 테스트"를 요청했다.

- **시나리오 A/B 대상 선정**: codebot의 `searchCode`/`getFileContent`는 GitHub REST API를 `ref` 파라미터 없이 호출하므로 항상 **origin/main(default branch)** 기준으로 동작한다. 따라서 테스트 시나리오는 origin/main에 이미 존재하는 파일을 대상으로 해야 한다. `CodeSearchTools.java`/`ObservabilityTools.java`/`LinearTools.java` 등 모든 `@Tool` 클래스가 `catch (Exception e) { ...e.getMessage()... }` 패턴으로 GitHub/observability API 실패 시 HTTP 응답 본문을 노출하지 않는 공통 문제를 발견했다 — 이를 시나리오 A(단일파일: CodeSearchTools.java)/B(다중파일: CodeSearchTools.java + ObservabilityTools.java)의 "사소하고 안전한 개선점"으로 채택했다.
- **Eureka 수정 범위**: codebot뿐 아니라 aiops도 동일하게 `discovery-service`가 `depends_on`에 list 형식으로만 들어있어 healthcheck 조건이 없다. 사용자가 "codebot + aiops 둘 다 수정"을 선택해 두 서비스 모두 serverA/B/C와 동일한 map 형식 `depends_on` + `condition: service_healthy`/`service_started` 패턴으로 통일한다.
- **정리 방침**: 테스트로 생성되는 Linear 이슈/GitHub PR/브랜치는 "테스트 후 정리"(사용자 선택)로, 단계 7에서 Cancel/Close/Delete 처리한다.
- **단계 5 (PR 템플릿) 추가 배경**: 단계 3에서 생성된 PR #6의 본문이 `.github/PULL_REQUEST_TEMPLATE.md`(5섹션)를 따르지 않고 자유 형식이었다. `/pr` 스킬은 이 템플릿을 명시적으로 따르도록 지시받지만, codebot의 `createFixPullRequest`는 `prBody` description에 "변경 요약/테스트 방법/영향도를 마크다운으로 작성"만 명시되어 있어 템플릿 구조를 모른다 — "관련 이슈"/"PR 전 체크리스트"는 내용이 고정(정적) 텍스트이므로 LLM에게 생성을 맡기지 않고 `createPullRequest()`에서 자동 추가, prBody description은 LLM이 작성할 나머지 3섹션(변경 요약/테스트 방법/영향도 및 주의사항)만 명시하는 방식으로 해결 (PR #8에서 5섹션 확인).
  - **재테스트 중 발견**: `LinearTools.createIssue`가 직무 라벨("인프라")을 도메인 라벨 자리에 선택해 Linear의 "한 그룹당 라벨 1개" 제약을 위반, GraphQL 오류로 이슈 생성이 반복 실패. `resolveLabelId()`가 라벨 이름만으로 ID를 찾고 부모 그룹(도메인/직무)을 검증하지 않은 것이 원인 — `resolveLabelId(labelName, expectedGroup)`로 변경해 `parent.name` 검증 추가.
  - **재테스트 중 발견 (미수정, 외부 이슈)**: GitHub `/search/code` API가 이 레포의 실재 파일에 대해 `total_count: 0`을 반환 (rate limit 여유 있음, Contents API로는 파일 확인됨) — GitHub 코드 검색 인덱싱 이슈로 추정. codebot에 정확한 파일 경로를 주면 `getFileContent`로 우회 가능.
- **단계 6 (ngrok+Slack) 추가 배경**: 단계 3/4는 `/internal/investigations`로 Slack을 거치지 않고 테스트했다. 이는 설계상 의도된 "Slack 없이 테스트" 경로이지만, 실제 Slack 알림 경로(Slack 이벤트 수신 → 라우팅 → 응답 게시) 자체는 아직 한 번도 검증되지 않았다. 로컬 docker-compose는 외부에서 접근 불가능하므로 ngrok으로 aiops를 노출해 Slack Event Subscriptions가 도달할 수 있게 한다.

### 검토했으나 채택하지 않은 대안

#### 대안 A: DataQueryTools/GRANT SQL 기반 시나리오
- 무엇: 새로 추가한 `DataQueryTools.java`나 `docs/db/codebot-readonly-grants-*.sql`을 시나리오 A/B 대상으로 사용
- 왜 안 썼나: 이 파일들은 현재 브랜치(`feature/codebot-data-query-tool`)의 미커밋 변경분이라 origin/main에 없음. `CodeSearchTools.searchCode`/`getFileContent`는 GitHub REST API로 origin/main만 조회하므로 codebot이 해당 파일을 찾거나 읽을 수 없다.

#### 대안 B: 가짜(인위적) 버그를 코드에 주입 후 테스트
- 무엇: 테스트 전용으로 의도적 버그를 커밋해 codebot이 발견하게 함
- 왜 안 썼나: 추가 커밋/되돌리기 작업이 필요해 범위가 커지고, "사소하고 안전한 개선점"이라는 설계 취지(실제 코드의 실제 개선점)와 거리가 멀다. 이미 origin/main에 실재하는 패턴(에러 응답 본문 미포함)으로 충분하다.

#### 대안 C: domainLabel을 Turn1 메시지에서 명시적으로 지정
- 무엇: "domainLabel은 '프로모션'으로 등록해줘"처럼 메시지에 라벨을 못박음
- 왜 안 썼나: 이번 테스트의 목적은 LLM의 자연스러운 도구 호출 흐름(에이전트가 스스로 라벨/내용을 결정)이 실제로 동작하는지 확인하는 것. 라벨을 지정하면 에이전트의 자율 판단을 테스트하지 못한다. 7개 라벨(주문/결제/프로모션/유저/백엔드/프론트엔드/인프라)이 모두 Linear 팀(Michi2012)에 존재함을 확인했으므로(`list_issue_labels`), 어떤 라벨을 선택해도 `resolveLabelId`는 성공한다.

### 기존 코드베이스 컨벤션
- `@Tool` 에러 처리 패턴(개선 대상): `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`, `ObservabilityTools.java`, `LinearTools.java`, `KubernetesTools.java`, `PullRequestTools.java`, `DataQueryTools.java` — 모두 `catch (Exception e) { log.warn(...); return "... 실패: " + e.getMessage(); }` 패턴
- 멀티턴 조사 흐름: `POST /internal/investigations` (`InvestigationController`) → `CodebotAgentService.investigate(conversationId, message)`, `MessageChatMemoryAdvisor`로 `conversationId`별 대화 메모리 유지 (Turn1→Turn2 연속 대화 가능)
- docker-compose `depends_on` map 형식 패턴: `server-a`/`server-b`/`server-c` (200-360행) — healthcheck 있는 서비스는 `condition: service_healthy`, 없는 서비스는 `condition: service_started`, init 컨테이너는 `condition: service_completed_successfully`

### 관련 파일/위치
- `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` — 시나리오 A 대상 (searchCode 49-52행, getFileContent 72-75행)
- `codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java` — 시나리오 B 대상 (Loki/Tempo/Pyroscope/Prometheus catch 블록)
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — `investigate()` 진입점, `SYSTEM_PROMPT`
- `codebot/src/main/java/codebot/codebot/api/InvestigationController.java` — `POST /internal/investigations`
- `docker-compose.yml` — aiops(736-742행)/codebot(774-782행) `depends_on` 수정 대상, discovery-service(212-238행) healthcheck 참고
- `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java` — 단계 5 대상, `createFixPullRequest`의 `prBody` `@ToolParam`(59행), `createPullRequest()`(154-168행)에서 `Closes {issueIdentifier}` append 부분
- `codebot/src/main/java/codebot/codebot/tools/LinearTools.java` — 단계 5 진행 중 `resolveLabelId()`에 부모 그룹 검증 추가 (도메인/직무 라벨 혼동 방지)
- `.github/PULL_REQUEST_TEMPLATE.md` — 단계 5에서 따라야 할 5섹션 구조 (변경 요약/관련 이슈/테스트 방법/영향도/PR 전 체크리스트)
- `aiops/src/main/java/aiops/aiops/router/SlackEventController.java`(`/slack/events`), `SlackBotClient.java`(`chat.postMessage`) — 단계 6에서 검증할 Slack 알림 경로

### 외부 참조
- Linear 팀 "Michi2012" 라벨 목록 (`list_issue_labels`로 확인): 도메인(주문/결제/프로모션/유저), 직무(백엔드/프론트엔드/인프라) 7개 모두 존재

## [진행 중] codebot 코드 검색 신뢰성 개선 및 도구 확인 절차 정비 (git-sync 사이드카)

### 왜 이 방식을 선택했는가
"codebot 전체 기능 E2E 스모크 테스트" 단계 5 재테스트 중, GitHub `/search/code` API가 이 PUBLIC 레포의 실재 파일(`CodeSearchTools.java` 등)에 대해 `total_count: 0`을 반환하는 문제를 발견했다(Contents API로는 파일 존재 확인됨, rate limit도 정상 — GitHub 코드 검색 인덱싱 측 외부 이슈로 추정). 이 문제의 근본 해결책을 사용자와 논의한 결과:

- **로컬 레포 사본 + grep**: 업계에서 LLM 코드 에이전트(Claude Code, Aider 등)가 코드를 찾는 표준 방식은 원격 검색 API가 아니라 로컬 클론 + grep/ripgrep이다. GitHub `/search/code`의 인덱싱 지연/신뢰성 문제를 원천 회피한다.
- **git-sync 사이드카 + emptyDir (PVC+CronJob 아님)**: codebot은 `replicas: 1`(helm/promotion-app/values.yaml 확인)이고 `ChatMemoryConfig.java`가 `InMemoryChatMemoryRepository`를 사용해 멀티 레플리카 자체가 부적합한 구조다. 따라서 PVC의 RWO 멀티노드 attach 충돌이나 RWX(EFS) 같은 인프라가 필요 없고, Pod 내부에서 emptyDir을 공유하는 사이드카로 충분하다. `git-sync`(`registry.k8s.io/git-sync/git-sync`)는 "Pod에 git repo 최신 사본을 유지"하는 업계 표준 솔루션(ArgoCD repo-server 등에서 사용)으로, 직접 만든 alpine/git 스크립트보다 적합하다.
- **동기화 주기 1분**: codebot은 Slack 멘션 시점에 즉석으로 코드를 검색하므로, 이슈 대응 중 방금 머지된 변경사항을 놓치지 않는 게 중요하다. 이 repo 규모에서 1분 간격 fetch는 GitHub API 부담이 거의 없다(토큰 사용 시 5000/hr 한도 대비 1분=60/hr).
- **검색 구현: `git grep` ProcessBuilder 셸아웃**: 순수 Java `Files.walk` + 문자열 매칭도 가능하지만, repo가 커지거나 검색 기능(정규식, 파일타입 필터, `.git`/`build/` 제외)이 확장될수록 grep이 이미 해결한 문제를 재구현하게 된다. `git grep`은 gitignore 인식, 바이너리 제외, 정규식을 기본 제공한다. 비용은 `codebot/Dockerfile`에 git 설치 + `ProcessBuilder` 인자를 `List<String>`로 구성(CLAUDE.md 커맨드 인젝션 방지 규칙, 기존 KubernetesTools와 동일 패턴)하는 정도다.
- **`searchCode` 완전 대체, `getFileContent`도 로컬화**: `searchCode`(GitHub API, 버그 있음)는 이름/시그니처를 유지한 채 내부 구현만 로컬 grep으로 교체한다. `getFileContent`(Contents API, 정상 동작 중)도 함께 로컬 파일 읽기로 변경하기로 결정 — 두 메서드 모두 로컬화되면 `CodeSearchTools`에서 `githubClient`/`githubOwner`/`githubRepo` 의존성 자체가 불필요해진다(orphan cleanup 대상).
- **도구 호출 확인 절차 명시 (createIssue 자동 / createFixPullRequest 확인)**: 이전 대화에서 "이슈 생성은 자동, PR 생성만 확인"이 합리적이라는 데 합의했다 — 이슈 생성은 되돌리기 쉬운 낮은 리스크 작업이고, PR 생성은 브랜치+커밋+실제 코드 변경이라는 더 큰 결과를 가져온다. 그런데 현재 `CodebotAgentService`의 SYSTEM_PROMPT에는 확인 절차 지시가 전혀 없어 LLM이 매번 비결정적으로 "물어볼지"를 판단하고 있다 — checklist.md "발견 사항"(단계 4/5)에 이 비결정성이 반복 관찰된 사례가 기록되어 있다(같은 시나리오에서 한 번은 즉시 호출, 한 번은 "진행할까요?" 확인 후 호출). 이번 plan에 `createIssue`(확인 없이 바로 호출)/`createFixPullRequest`(변경 내용 제시 후 동의 필요)에 대한 명시적 규칙을 SYSTEM_PROMPT에 추가해 이 행동을 결정론적으로 만든다.
- **ngrok Slack E2E 추가**: 로컬 검색 변경과 확인 절차 명시는 모두 LLM 프롬프트/도구 동작에 대한 변경이라 단위 테스트만으로는 실제 효과를 검증하기 어렵다. 실제 Slack 워크스페이스에서 메시지를 보내 (1) 검색이 정상 동작하는지, (2) 이슈가 추가 확인 없이 자동 생성되는지, (3) PR 생성 전 동의를 요청하는지를 직접 확인하기로 했다.

### 검토했으나 채택하지 않은 대안

#### 대안 A: PVC + CronJob
- 무엇: codebot Pod와 별도로 PVC에 주기적으로 `git pull`하는 CronJob을 두고, codebot이 그 PVC를 마운트
- 왜 안 썼나: codebot.replicas=1 환경에서는 RWO PVC로 충분하지만, CronJob pod와 codebot pod가 다른 노드에 스케줄되면 RWO volume의 단일 노드 attach 제약으로 충돌/대기가 발생할 수 있다. 멀티 레플리카로의 확장 필요성도 없어(ChatMemory가 in-memory) PVC가 주는 "Pod 재시작 후 캐시 유지" 이점도 크지 않다.

#### 대안 B: Sourcegraph 등 중앙 코드 인덱스 서비스
- 무엇: Sourcegraph 같은 별도 코드 검색 서비스를 클러스터에 배포하고 codebot이 그 API를 호출
- 왜 안 썼나: Postgres+인덱서+frontend 등 여러 컴포넌트로 구성된 애플리케이션 전체를 운영해야 해서, 단일 codebot Pod·단일 모노레포 규모에는 명백한 오버킬이다. 수십 개 repo·여러 팀이 크로스레포 검색을 자주 쓰는 규모에서나 투자 대비 이득이 난다.

#### 대안 C: 순수 Java(`Files.walk`) 기반 검색
- 무엇: ProcessBuilder/git 의존성 없이 Java NIO로 디렉토리를 순회하며 문자열/정규식 매칭
- 왜 안 썼나: 지금은 단순하지만 `.git`/`build/` 등 제외 처리, 정규식, 파일타입 필터 등을 직접 구현해야 해 확장성 면에서 grep이 이미 해결한 문제를 재구현하게 된다 (사용자가 "확장성 고려 시" 의견을 요청해 git grep으로 결정).

#### 대안 D: getFileContent는 GitHub Contents API 유지 (searchCode만 교체)
- 무엇: 정상 동작 중인 `getFileContent`는 건드리지 않고 `searchCode`만 로컬화 (Surgical Changes 원칙에 더 부합하는 최소 변경)
- 왜 안 썼나: 사용자가 명시적으로 "getFileContent도 로컬 파일 읽기로 변경"을 선택함 — GitHub API 호출 제거로 rate limit 영향이 완전히 없어지고, 두 메서드가 동일한 로컬 경로 기반으로 일관되게 동작하는 것을 우선했다.

### 기존 코드베이스 컨벤션
- `@Tool` 패턴: `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` 자체가 패턴 예시 (`@Slf4j @Component`, 생성자 주입, `@Tool(description = """...""")`, try/catch 후 설명적 에러 문자열 반환, `truncate()` 10,000자 제한)
- 외부 프로세스 호출(List<String> 인자 구성): `codebot/src/main/java/codebot/codebot/tools/KubernetesTools.java` — kubectl/helm 명령을 `List<String>`로 구성하는 기존 패턴을 `git grep` 셸아웃에도 동일하게 적용
- Helm 값 주입: `helm/promotion-app/values.yaml`의 `codebot:` 섹션, `--set` 패턴

### 관련 파일/위치
- `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` — 전면 재작성 대상 (searchCode/getFileContent)
- `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java` — `MockRestServiceServer` 기반 테스트를 로컬 파일시스템(`@TempDir`) 기반으로 재작성
- `codebot/Dockerfile` — git 패키지 설치 추가
- `codebot/src/main/resources/application.yaml`, `codebot/src/test/resources/application.yaml` — `codebot.repo.local-path` 설정 추가
- `docker-compose.yml` — `codebot-git-sync` 서비스 + `codebot-repo` named volume 신규 추가
- `helm/promotion-app/templates/codebot/deployment.yaml`, `helm/promotion-app/values.yaml` — git-sync 사이드카 컨테이너 + emptyDir 볼륨 추가
- `helm/promotion-app/values.yaml:289` (`codebot.replicas: 1`), `codebot/src/main/java/codebot/codebot/config/ChatMemoryConfig.java` — 이번 설계가 사이드카+emptyDir로 결정된 근거(멀티 레플리카 부적합 확인)
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — SYSTEM_PROMPT의 "## 이슈 생성 (createIssue)", "## 코드 수정 (createFixPullRequest)" 섹션에 확인 절차 규칙 추가

### 외부 참조
- `registry.k8s.io/git-sync/git-sync` — Kubernetes SIG 공식 git-sync 이미지 (정확한 env var/플래그명은 구현 시 공식 문서로 재확인 필요)

## [진행 중] codebot 이슈/PR 생성 UX 개선

### 왜 이 방식을 선택했는가
"codebot 코드 검색 신뢰성 개선" plan 단계6 ngrok Slack E2E에서 시나리오2(코드 개선점 분석 후 이슈 등록)와 시나리오3(코드 수정 후 PR 생성 동의)을 실행한 결과, 3가지 UX 문제가 발견됐다:

- **Q1: createIssue description 템플릿 분기**: 현재 SYSTEM_PROMPT의 "## 이슈 생성 (createIssue)" description 규칙("배경, 증상, 근본 원인(미확인 부분은 '가설:' 표기), 조사 근거")은 운영 장애 RCA를 염두에 둔 것이다. 시나리오2처럼 "코드 개선점 분석해서 이슈로 등록해줘"라는 비-장애 요청에도 동일 템플릿이 적용되어, 실제로 생성된 MIC-14의 "가설" 섹션에는 "위 개선사항들을 적용하면 안정성이 향상될 것임" 같은 내용 없는 문장만 채워졌다. 사용자가 직접 "가설에도 코드 부분 어디가 문제인지 코드를 보여줘야 하는거 아니야?"라고 지적 — description 템플릿을 조사 유형(운영 이상 vs 코드 개선)에 따라 분기해, 코드 개선 유형에는 "가설" 표기 없이 항목별 코드 인용 + 개선 방향을 작성하도록 했다. `## 조사 원칙` 3번("확정되지 않은 원인은 '가설:'로 표기")은 일반 조사 응답에 대한 규칙이라 그대로 유지하고, createIssue description 규칙만 분기한다.
- **Q2: Slack mrkdwn 변환을 코드 레벨에서 처리**: `SlackBotClient.postMessage`가 LLM이 생성한 표준 마크다운(`**bold**`, `### heading`, `[text](url)`)을 그대로 Slack `chat.postMessage`의 `text`로 전송해, Slack에서 `**`가 글자 그대로 노출된다. SYSTEM_PROMPT에 "Slack mrkdwn 문법을 사용하라"는 지시를 추가하는 대안도 있었지만, "코드 검색 신뢰성 개선" plan에서 이미 "LLM의 비결정적 동작은 프롬프트보다 코드 레벨 처리가 안정적"이라는 결론을 내렸다(SYSTEM_PROMPT 확인 절차 규칙 추가 후에도 여전히 비결정적 동작이 관찰됨, checklist "발견 사항" 참고). 동일한 이유로, `postMessage` 직전에 정규식 기반 `convertMarkdownToSlackMrkdwn()`을 추가해 결정론적으로 변환한다.
- **Q3: previewDiff 도구 + java-diff-utils**: 시나리오3에서 codebot이 PR 생성 동의를 구할 때 수정된 파일 "전체"를 Slack에 보여줘 어디가 바뀌었는지 파악하기 어렵다는 문제가 제기됐다. LLM이 직접 unified diff 형식의 텍스트를 생성하게 하는 방법도 있지만, line 번호/context 정확도가 낮아 부적합하다고 판단했다. 대신 `getFileContent`(기존 파일 내용)와 LLM이 작성한 `newContent`를 코드(java-diff-utils)로 비교해 정확한 unified diff를 계산하는 `previewDiff` 도구를 추가하고, createFixPullRequest 호출 전 이 도구로 diff를 보여주도록 SYSTEM_PROMPT에 안내한다. java-diff-utils는 IntelliJ/다수 코드 리뷰 도구가 사용하는 소규모 검증된 라이브러리로, 직접 diff 알고리즘을 구현하는 것보다 리스크가 적다.

### 검토했으나 채택하지 않은 대안

#### 대안 A (Q1): createIssue description 템플릿은 그대로 두고, "가설" 섹션 내용을 사용자가 직접 보강
- 무엇: SYSTEM_PROMPT를 수정하지 않고, 부실한 "가설" 섹션은 이슈 생성 후 사용자가 Linear에서 직접 수정
- 왜 안 썼나: 매번 동일한 패턴(코드 개선 요청 → 내용 없는 "가설" 섹션)이 반복될 것이 예상되고, 프롬프트 레벨에서 한 번 고치면 향후 모든 코드 개선 이슈에 일관되게 적용된다.

#### 대안 B (Q2): SYSTEM_PROMPT에 "Slack mrkdwn 문법(`*bold*`, `<url|text>`)을 사용해 응답하라" 지시 추가
- 무엇: LLM이 처음부터 mrkdwn 문법으로 응답하도록 프롬프트 지시
- 왜 안 썼나: "코드 검색 신뢰성 개선" plan에서 SYSTEM_PROMPT에 확인 절차 규칙을 추가한 뒤에도 LLM이 규칙을 일관되게 따르지 않는 비결정성이 반복 관찰됐다(checklist "발견 사항" 단계4/5/6). 마크다운 문법은 LLM의 사전학습된 강한 습관이라 프롬프트만으로 완전히 억제하기 더 어려울 것으로 판단, 코드 레벨 후처리로 결정.

#### 대안 C (Q3): PR 생성 후 GitHub PR 페이지 링크만 안내 (Slack에서 diff 미표시)
- 무엇: previewDiff 없이, PR 생성 완료 후 "변경 내용은 PR 링크에서 확인하세요"로 안내
- 왜 안 썼나: 사용자가 "PR 생성 전" 동의 단계에서 변경 내용을 파악하고 싶어 하는 것이 원래 질문의 핵심이었고, "현업이라면 어떻게 했을지" 질문에 대한 답으로도 diff 미리보기가 표준적인 코드 리뷰 관행에 더 부합한다.

#### 대안 D (Q3): 직접 unified diff 알고리즘 구현 (hand-rolled, 신규 의존성 없음)
- 무엇: java-diff-utils 없이 Java로 직접 line-by-line diff 알고리즘 구현
- 왜 안 썼나: 사용자에게 제시한 트레이드오프(hand-rolled 구현 리스크 > 소규모 의존성 추가 리스크)에 대해 A안(java-diff-utils)으로 명시적으로 결정함.

### 기존 코드베이스 컨벤션
- `@Tool` 패턴: `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`의 `getFileContent`/`searchCode` — `@Tool(description = """...""")`, try/catch 후 설명적 에러 문자열 반환, `truncate()` 10,000자 제한. `previewDiff`도 동일 패턴을 따른다.
- SYSTEM_PROMPT 섹션 구조: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`의 `## 이슈 생성 (createIssue)`/`## 코드 수정 (createFixPullRequest)` 섹션 — 불릿 목록으로 호출 조건/필드 작성 규칙을 명시하는 기존 형식을 유지하며 항목 추가/분기한다.
- Slack 클라이언트 테스트: `aiops/src/test/java/aiops/aiops/router/SlackBotClientTest.java` — `MockRestServiceServer`, `@DisplayName`, given/when/then 한글 주석 컨벤션을 신규 테스트에도 동일 적용.

### 관련 파일/위치
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — SYSTEM_PROMPT의 "## 이슈 생성 (createIssue)" description 분기(Q1), "## 코드 수정 (createFixPullRequest)" previewDiff 안내 추가(Q3)
- `aiops/src/main/java/aiops/aiops/router/SlackBotClient.java`, `aiops/src/test/java/aiops/aiops/router/SlackBotClientTest.java` — 마크다운→mrkdwn 변환(Q2)
- `codebot/build.gradle` — java-diff-utils 의존성 추가(Q3)
- `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`, `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java` — previewDiff 도구 추가(Q3)
- `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java` — previewDiff와 직접 연계되지는 않지만, createFixPullRequest의 newContent 흐름 이해를 위한 참고 (변경 없음)

### 외부 참조
- java-diff-utils (Maven Central, `io.github.java-diff-utils:java-diff-utils`) — unified diff 생성 라이브러리, 정확한 버전은 단계3에서 확인

## [진행 중] codebot 라우팅 안정성 개선 + 이슈 description 코드 인용 강화

### 왜 이 방식을 선택했는가
"codebot 이슈/PR 생성 UX 개선" plan 완료 후 ngrok Slack E2E를 재검증하는 과정에서, 시나리오2가 정상 동작(MIC-15가 올바른 "코드 품질/구조 개선" 템플릿으로 생성됨)한 뒤 "pr 날려줘"에 대해 codebot이 실제 파일 내용이 아닌 가상의 `com.michi.tools.CodeSearchTools`/`search()` 클래스로 diff를 만들어내는 hallucination이 발생했다. 이어서 "생성해"/"너가 판단해" 같은 짧은 후속 메시지를 보내자 매번 "인프라 문제인지 코드/기능 문제인지 알려주세요."라는 동일한 문장이 반복 반환되며 멈췄다.

원인을 추적한 결과, 이 고정 문구는 codebot이 아니라 `aiops.aiops.router.RouterService`의 `UNKNOWN_GUIDANCE` 상수였다. `RouterService.handleAppMention`은 매 메시지마다 `IntentClassifierService.classify(text)`를 호출하는데, 이 분류기는 스레드 맥락 없이 메시지 1건만 보고 INFRA/CODE/UNKNOWN을 판단한다. "생성해"처럼 맥락이 없으면 짧은 메시지는 거의 항상 UNKNOWN으로 분류되고, 이 경우 codebot/InfraChatAgentService 어느 쪽에도 메시지가 전달되지 않은 채 고정 안내문만 반환된다 — 이전 plan의 Q1(createIssue description 분기)과는 무관한, 라우팅 계층의 사전 존재 이슈다.

해결 방향으로 (a) INFRA/CODE 분기를 없애고 codebot으로 통합, (b) 분류기에 스레드 히스토리 전달, (c) 스레드별 직전 라우트를 sticky 캐시로 재사용을 검토했다. (a)는 `InfraChatAgentService`가 가진 `propose*`(조치 제안 + Slack 승인/거절 버튼) 기능을 codebot으로 옮겨야 하는 큰 변경이라 제외했다. (b)는 분류기 입력 구조/프롬프트 변경이 필요해 이번 문제 규모에 비해 과하다고 판단했다. (c)는 `RouterService` 한 파일(+테스트)만 변경하면 되고, "분류 결과가 INFRA/CODE면 우선 사용 + 캐시 갱신, UNKNOWN일 때만 캐시 재사용"이라는 규칙으로 주제 전환에도 대응할 수 있어 선택했다.

description 코드 인용 강화는, 같은 E2E에서 관찰된 PR diff hallucination(존재하지 않는 클래스/메서드로 diff를 생성)이 createIssue description의 코드 인용에서도 동일하게 발생할 수 있다고 판단해, "코드 품질/구조 개선 요청" 항목에 `getFileContent`로 조회한 실제 내용을 파일 경로와 함께 그대로 인용하라는 문구를 추가했다.

### 검토했으나 채택하지 않은 대안

#### 대안 A: INFRA/CODE 분기를 제거하고 모든 메시지를 codebot으로 라우팅
- 무엇: `RouterService`의 사전 분류 단계를 없애고 `IntentClassifierService`/`InfraChatAgentService` 분기를 제거
- 왜 안 썼나: `InfraChatAgentService`는 `observabilityTools`/`kubernetesTools`와 함께 조치 제안(`propose*` + Slack 승인/거절 버튼) 기능을 갖고 있는데 codebot에는 이 기능이 없다. 이를 이전하는 것은 이번 문제(짧은 후속 메시지가 dead-end되는 것) 해결에 비해 훨씬 큰 변경이다.

#### 대안 B: IntentClassifierService에 스레드 최근 메시지 히스토리 전달
- 무엇: `classify()`에 최근 N개 메시지를 함께 전달해 맥락 기반으로 분류
- 왜 안 썼나: 분류기의 입력 구조와 프롬프트를 변경해야 하고 호출당 비용도 늘어난다. sticky 캐시로 동일 증상(짧은 후속 메시지의 UNKNOWN 분류)을 훨씬 작은 변경으로 해결할 수 있다.

#### 대안 C: Linear Project/Milestone 고정 연결 추가
- 무엇: `LinearTools.createIssue`의 `issueCreate` 입력에 고정 `projectId`/`projectMilestoneId`(또는 둘 중 하나)를 추가
- 왜 안 썼나: 워크스페이스에는 "프로모션 시스템 구축"(전체 프로젝트 컨테이너, M1~M4 Milestone으로 영역별 진행 관리) 1개 Project만 존재한다. 봇이 생성하는 모든 이슈를 여기에 강제 귀속시키는 것은 부적절하고, 현업에서도 봇이 생성한 이슈는 라벨까지만 부여하고 Project/Milestone 배정은 사람이 triage 시점에 결정하는 것이 일반적이다. 현재 동작(미지정)이 의도된 것으로 확인되어 변경하지 않는다.

### 기존 코드베이스 컨벤션
- 라우팅: `aiops/src/main/java/aiops/aiops/router/RouterService.java` — `IntentClassifierService.classify()` 결과(INFRA/CODE/UNKNOWN)에 따라 `InfraChatAgentService`/`CodebotClient`/정적 안내문(`UNKNOWN_GUIDANCE`)으로 분기, `threadTs`를 conversationId로 사용
- 라우터 테스트: `aiops/src/test/java/aiops/aiops/router/RouterServiceTest.java` — Mockito mock(`intentClassifierService`/`infraChatAgentService`/`codebotClient`/`slackBotClient`)을 직접 `new RouterService(...)`에 주입하는 패턴
- SYSTEM_PROMPT 패턴: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — 텍스트 블록(`"""..."""`) 내 항목별 불릿으로 지시, 이번 plan 단계3도 동일하게 기존 불릿에 문구를 추가하는 형태로 적용

### 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/router/RouterService.java` — sticky 캐시 추가 지점
- `aiops/src/test/java/aiops/aiops/router/RouterServiceTest.java` — 신규 sticky 캐시 테스트 추가 지점
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — SYSTEM_PROMPT의 "코드 품질/구조 개선 요청" 항목에 코드 인용 출처 강화 문구 추가
- `aiops/src/main/java/aiops/aiops/router/IntentClassifierService.java`, `aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java` — 이번 plan에서 변경하지 않지만 라우팅 동작 이해를 위한 참고

### 외부 참조
- 없음

## [진행 중] codebot PR 흐름 신뢰성 개선 — diff 인용 + filePath 자동보정

### 왜 이 방식을 선택했는가
"codebot 라우팅 안정성 개선 + 이슈 description 코드 인용 강화" plan 완료 후 진행한 ngrok Slack E2E에서 시나리오3(코드 수정 → PR 생성 동의)을 재검증하는 과정에서 3가지 문제가 발견되었다:

1. MIC-16 이슈의 코드 인용이 getFileContent로 조회한 실제 내용이 아니라 추측/재구성된 pseudo-code였다.
2. codebot이 "위의 diff를 확인하라"고 안내했지만, 실제 응답에는 previewDiff가 반환한 diff 텍스트가 포함되어 있지 않았다.
3. createFixPullRequest 호출이 GitHub API 404로 실패했다.

docker logs와 코드를 직접 확인한 결과, 1과 2는 "도구 호출은 정확한 데이터로 성공했지만, LLM이 그 결과를 이후 응답에서 충실히 재사용하지 않는다"는 동일한 패턴이었다. 3은 createFixPullRequest에 전달된 filePath가 같은 스레드의 이전 턴에서 getFileContent/previewDiff에 사용했던 실제 경로와 달랐기 때문이었다.

1은 직전 plan(라우팅 안정성 개선)의 단계3(description 코드 인용 출처 강화)으로 이미 부분적으로 다뤄졌으므로 이번 plan에서는 2(diff 인용)와 3(filePath)에 집중한다.

3의 원인을 더 들여다보면, `ChatMemoryConfig`가 `MessageWindowChatMemory.builder().maxMessages(20)`으로 구성되어 있다 — 대화가 길어지면 오래된 메시지(이전 턴의 getFileContent/previewDiff 호출·결과 포함)가 컨텍스트에서 제거된다. createFixPullRequest는 보통 previewDiff로 동의를 구한 뒤 1-2턴 후에 호출되므로, 그 사이 경로 정보가 컨텍스트에서 밀려나면 "previewDiff(filePath, ...)에 사용한 경로를 createFixPullRequest의 filePath에도 그대로 사용하라"는 프롬프트 지시만으로는 안정적으로 동작하지 않을 가능성이 높다.

이 때문에 filePath 문제(3)는 프롬프트 지시(대안 A) 대신 코드 레벨 자동보정(B)으로 해결하기로 사용자와 합의했다 — CodeSearchTools.readByBasename과 동일한 패턴(git ls-files 기반 정확 일치 → basename 단일 일치 시 자동 보정 → 0개/2개 이상이면 명시적 오류)을 PullRequestTools.createFixPullRequest에도 적용한다. 이 방식은 ChatMemory 상태와 무관하게 결정론적으로 동작한다.

추가로, PullRequestTools/CodeSearchTools의 `@ToolParam` 경로 예시가 모두 `"aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java"`로 동일한데, 이 경로는 실제로 존재하는 파일이다. LLM이 컨텍스트 유실로 이 예시를 그대로 filePath에 사용하면, 자동보정의 "정확히 일치" 분기를 그대로 통과해 의도하지 않은 파일(AiOpsAgentService.java)을 조용히 수정/diff하게 될 위험이 있다. 따라서 이번 plan에서는 세 `@ToolParam` 모두에서 구체적인 예시 경로를 제거하고 "이전 단계에서 확인한 실제 경로를 그대로 사용"하라는 안내로 대체한다 — 자동보정이 "정상 매칭"으로 오인하지 않고 "경로를 찾을 수 없음" 오류로 드러나도록 유도한다.

diff 인용(2)은 previewDiff가 반환하는 텍스트를 ```diff 코드 블록으로 감싸 반환하도록 변경(코드 레벨 1차 방어선)하고, SYSTEM_PROMPT에 "previewDiff 결과를 변형 없이 그대로 포함"하라는 지시를 보조로 추가한다.

### 검토했으나 채택하지 않은 대안

#### 대안 A (filePath 재사용): SYSTEM_PROMPT에 "previewDiff와 동일한 filePath를 createFixPullRequest에도 사용" 지시만 추가
- 무엇: 프롬프트 텍스트 수정만으로 filePath 일관성을 유도
- 왜 안 썼나: `ChatMemoryConfig.maxMessages(20)` 때문에 previewDiff 호출 시점의 filePath가 createFixPullRequest 호출 시점에는 컨텍스트에서 이미 제거되어 있을 수 있다. 직전 plan(라우팅 안정성 개선)의 description 코드 인용 지시도 같은 종류의 프롬프트 의존 해결책이었는데 이번 E2E에서 한계가 다시 확인되었다 — 코드 레벨(B)을 우선 적용하기로 사용자와 합의했다.

#### 대안 B (diff 인용): previewDiff 출력을 코드 블록으로 감싸지 않고 SYSTEM_PROMPT 지시만 추가
- 무엇: ```diff``` 감싸기 없이 "previewDiff 결과를 그대로 포함하라"는 지시만 추가
- 왜 안 썼나: 대안 A와 정확히 같은 한계 — previewDiff 호출과 그 결과를 다음 응답에서 재사용하는 사이의 LLM 비결정성을 코드가 아닌 프롬프트로만 통제하게 된다. ```diff``` 코드 블록 감싸기는 previewDiff의 반환값 자체를 Slack에 그대로 노출하기 좋은 형태로 만들어, "그대로 인용"을 코드 레벨에서 더 쉽게 만든다.

### 기존 코드베이스 컨벤션
- 경로 자동보정 패턴: `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`의 `readFile`/`readByBasename`/`listFiles` — `repoPath.resolve(path).normalize()`로 정확히 일치하는 파일 우선 사용, 없으면 `git ls-files` 결과에서 basename이 일치하는 파일을 찾아 0개/1개/2개 이상에 따라 분기. PullRequestTools.resolveFilePath도 동일 패턴을 따른다(공통 추출 없이 각자 보유).
- `@Tool`/`@ToolParam` 패턴: 동일 파일의 `getFileContent`/`previewDiff` — description에 호출 시점/반환값/실패 시 동작을 명시.
- 테스트 패턴: `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java` — `@TempDir Path repoDir` + `@BeforeEach`에서 git init/add/commit으로 임시 레포 구성. PullRequestToolsTest에도 동일 패턴 적용.

### 관련 파일/위치
- `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java` — filePath 자동보정 추가 지점(단계1)
- `codebot/src/test/java/codebot/codebot/tools/PullRequestToolsTest.java` — 자동보정 테스트 추가 지점(단계2)
- `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` — previewDiff 코드 블록 감싸기 + `@ToolParam` 예시 제거(단계3)
- `codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java` — 코드 블록 감싸기 검증(단계4)
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — previewDiff 결과 raw 인용 지시 추가(단계5)
- `codebot/src/main/java/codebot/codebot/config/ChatMemoryConfig.java` — maxMessages(20) eviction 원인 분석 참고(변경 없음)

### 외부 참조
- 없음
