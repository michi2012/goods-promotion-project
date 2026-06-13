# 맥락 노트: codebot Pyroscope 핫스팟 도구 추가 + 단일파일 코드수정/PR 워크플로우 (묶음 B)

## 왜 이 방식을 선택했는가

### Pyroscope 핫스팟 도구
- 묶음 A에서 CodebotAgentService의 "조사 원칙"을 "코드부터 → 인프라 보조"에서 "운영 상태부터 확인 → 코드 분석" 순서로 수정했다. 이 1단계(운영 상태 확인)에서 얻은 "클래스명/메서드명" 단서를 2단계(`searchCode`/`getFileContent`)에 넘기는 구조다.
- aiops의 `queryProfilerHotspots`는 "자체 CPU 시간 비율 | 메서드명" 형식으로 결과를 반환한다 — Loki(에러 로그)·Tempo(서비스 단위 지연)보다 훨씬 더 직접적으로 "어떤 메서드"를 가리키는 단서다. 1단계 도구로 자연스럽게 추가할 가치가 있다고 판단했다.
- 메서드 단위로 핫스팟이 좁혀지면, 묶음 B의 "단일 파일 수정" 신뢰도도 함께 올라간다 (핫스팟 메서드 = 수정 대상 파일의 강한 신호).
- aiops의 `pyroscopeClient`/`application.yaml` 기본값(`http://pyroscope:4040`)은 Helm 값 주입 없이 동작하므로, codebot도 동일 기본값을 그대로 사용 — Helm 변경 불필요.

### 단일파일 코드수정 + PR (Human-in-the-Loop)
- 묶음 B 설계 전, "완전 자동화(이슈 생성 즉시 PR까지)" vs "사용자 명시 지시 시에만 PR 생성"을 논의했다. 처음에는 "RCA 신뢰도가 충분하면 자동 PR, 머지는 게이트"로 절충하려 했으나, 사용자가 "PR 생성 자체가 팀 피로/아키텍처 합의 비용을 발생시킨다"는 더 근본적인 논거를 제시해 이 절충이 틀렸음을 인정하고 철회했다.
- 최종 결론: Linear 이슈(RCA+가설 포함)는 자동 생성(묶음 A에서 이미 구현됨)하되, **코드 수정+PR은 같은 Slack 스레드에서 사용자가 명시적으로 지시했을 때만** 동작한다 (GitHub Copilot Workspace류의 ChatOps 패턴).
- 코드 수정 메커니즘은 git clone/샌드박스가 아닌 **GitHub REST API**(Contents API + Git Data API의 ref 부분만)로 한정했다 — codebot은 이미 `githubClient`(GITHUB_TOKEN)를 갖고 있고, 단일 파일 교체에는 clone이 필요 없다.
- "단일 파일"은 LLM의 판단(소프트 제약)에 더해 **도구 시그니처 자체가 `filePath` 1개만 받도록 구조적으로 강제**한다 — 멀티파일이 필요하다고 LLM이 판단하면 이 도구를 호출하지 않고 텍스트로 안내한다.

### 보호 경로 가드 (단계 5)
- 구현 완료 후 자체 점검에서, `createFixPullRequest`가 레포 내 임의 경로에 쓸 수 있다는 점을 발견했다. LLM이 조사 맥락을 잘못 해석해 `.github/workflows/*`(CI/CD 설정)나 `.env`(환경변수/비밀) 같은 민감 경로를 "수정 대상 파일"로 판단하면 그대로 커밋될 위험이 있다.
- PR을 사람이 머지 전 리뷰한다는 1차 방어선은 이미 있지만, 도구 차원에서 `.github/`, `.env`/`.env.*` 경로를 호출 초기에 차단해 GitHub API 호출 자체가 발생하지 않도록 하는 2차(사실상 1차) 방어선을 추가하기로 했다.
- 단순한 prefix 매칭으로 충분하다고 판단 — 이 두 경로 외의 일반 소스 코드(`application.yaml` 포함, 비밀 값은 env var 치환이라 파일 자체에 비밀이 없음)는 codebot의 정상적인 수정 대상이 될 수 있으므로 deny-list를 더 넓히지 않았다.

### 브랜치명을 "결정적(deterministic)"으로 바꾼 이유
- 처음에는 LLM이 자유롭게 브랜치명(`feature/mic-12-fix-discount-calc` 등)을 생성하는 설계였다. 그러나 "동일 이슈에 대해 두 번째로 '고쳐서 PR' 요청이 오면 어떻게 되는가?"라는 질문에서, 자유 생성 브랜치명은 매번 새 브랜치/PR을 만들어 레포에 쓰레기가 쌓이는 문제(옵션 2)나, 브랜치 충돌로 실패하는 문제(옵션 1)로 이어졌다.
- 채택한 방식: 브랜치명을 `feature/{issueIdentifier 소문자}-codebot-fix`로 **이슈 번호 기반 결정적 이름**으로 고정. 이렇게 하면:
  - 재요청 시 브랜치 존재 여부를 1번의 GET으로 확인 가능 (`GET /git/refs/heads/{branch}` → 404면 신규, 200이면 기존)
  - 기존 브랜치가 있으면 GitHub Contents API의 `PUT /contents/{path}`(같은 API, `branch` 파라미터만 동일)로 "추가 커밋"이 자연스럽게 쌓인다 — git clone/checkout/force-push 없이, V2(진짜 git 상태관리)의 핵심 가치를 V1 아키텍처 그대로 얻는다.
  - 기존 브랜치의 PR을 찾아 재사용(`GET /pulls?head={owner}:{branch}&state=open`)하므로 ChatMemory에 "내가 만든 PR이 뭐였지"를 별도로 기억할 필요가 없다 — GitHub 자체가 source of truth.
- 이슈의 "가설:" 표기 관행(묶음 A)과도 맞물린다 — 첫 RCA 가설이 틀렸을 때 같은 이슈/브랜치/PR 위에서 자연스럽게 수정이 누적된다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 에러 반환 + 수동 작업 유도 (재요청 시 브랜치 충돌을 그대로 에러로 노출)
- 무엇: 동일 이슈 재요청 시 `POST /git/refs`가 422(Reference already exists)를 반환하면, 정제된 메시지로 "기존 PR에서 수동으로 진행해주세요"라고 안내하고 종료.
- 왜 안 썼나: 묶음 A의 "가설:" 기반 RCA는 첫 시도가 틀릴 수 있음을 이미 전제하는데, 재시도를 막아버리면 그 수정 루프 자체가 끊긴다. "결정적 브랜치명 + find-or-create" 방식이 거의 같은 복잡도로 더 나은 결과(추가 커밋 자동 반영)를 준다.

### 대안 B: 타임스탬프/해시 슬러그로 브랜치명 충돌 회피
- 무엇: 재요청마다 `feature/mic-12-fix-timeout-1a2b`, `...-3c4d`처럼 새 브랜치/PR을 생성.
- 왜 안 썼나: 사용자도 인정한 대로 레포에 "쓰레기 브랜치/PR"이 무한정 쌓이는 운영 비용이 생긴다.

### 대안 C: 진짜 git clone/checkout 기반 상태관리 (V2)
- 무엇: codebot이 ChatMemory에 "이전에 만든 브랜치 SHA"를 추적하고, 로컬 clone → checkout → 수정 → push.
- 왜 안 썼나: 묶음 B 범위(단일 파일, 명시적 트리거)에서는 과한 복잡도. "결정적 브랜치명 + Contents API"로 동일한 핵심 가치(추가 커밋 누적)를 훨씬 단순하게 얻을 수 있다.

### 대안 D: GitHub API 호출을 세분화된 도구 3개(브랜치생성/파일커밋/PR생성)로 분리
- 무엇: LLM이 단계별로 각 도구를 순서대로 호출.
- 왜 안 썼나: 중간 실패 시 "브랜치만 생성되고 PR은 없음" 같은 부분 상태가 남을 위험. 통합 도구 1개(`createFixPullRequest`)가 원자적이고, 묶음 B의 좁은 시나리오(단일파일+사람트리거)에서 충분.

## 기존 코드베이스 컨벤션
- 도구 클래스: `@Component` + 생성자에 `@Qualifier`로 명명된 `RestClient` 주입 (`codebot/src/main/java/codebot/codebot/tools/*.java`)
- 외부 API 실패 처리: try/catch로 감싸 "실패 메시지: {원인}" 문자열을 반환 (예외를 던지지 않음) — LLM이 결과를 보고 다음 행동을 결정
- JSON 파싱: `com.fasterxml.jackson.databind.ObjectMapper`/`JsonNode`로 응답을 파싱해 요약 문자열 생성 (예: `CodeSearchTools.extractSearchResults`, aiops `ObservabilityTools.extractHotspots`)
- 테스트: `MockRestServiceServer.bindTo(builder).build()`로 RestClient 응답을 모킹 (`codebot/src/test/java/codebot/codebot/tools/CodeSearchToolsTest.java` 참고)
- `RestClientConfig`: 외부 서비스별 `RestClient` 빈을 `buildWithTimeout(baseUrl)` 헬퍼로 생성 (`codebot/src/main/java/codebot/codebot/config/RestClientConfig.java`)

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java` — `queryProfilerHotspots`/`extractHotspots` 복제 원본
- `aiops/src/main/java/aiops/aiops/config/RestClientConfig.java` — `pyroscopeClient` 빈 정의 원본
- `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java` — `githubClient` 사용 패턴 (`searchCode`/`getFileContent`), `PullRequestTools`가 동일 생성자 패턴으로 `githubOwner`/`githubRepo` 주입받음
- `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java` — SYSTEM_PROMPT(조사 원칙/이슈 생성/코드 수정), ChatMemory 기반 멀티턴
- `.claude/commands/pr.md` — `Closes MIC-{n}` 컨벤션, PR 본문 5섹션 템플릿(간소화하여 prBody 가이드에 반영)

## 외부 참조
- GitHub REST API: Git References (`/git/refs`), Repository Contents (`/repos/{owner}/{repo}/contents/{path}`), Pulls (`/repos/{owner}/{repo}/pulls`)

---

# 맥락 노트: 데드락 의심 롤링 재시작 시 스레드 덤프 자동 확보 (묶음 C)

## 왜 이 방식을 선택했는가
- 외부 리뷰에서 "재시작 전 스레드 덤프 추출" 옵션을 Slack 버튼으로 제안하라는 의견이 있었으나, 토론 결과 **항상 자동으로 먼저 덤프를 떠서 승인 메시지에 포함**하는 방식(선택지 A)을 채택했다. 리뷰의 본질적 목표(재시작으로 사후 증거가 사라지는 것 방지)는 동일하게 달성하면서, Slack 인터랙션 분기·`ActionApprovalService`의 `approve`/`reject` consume 문제를 모두 회피할 수 있다.
- 베이스 이미지가 `eclipse-temurin:21-jre-jammy`(JRE only)라 `jstack`/`jcmd`(JDK 전용)는 `kubectl exec`로 사용할 수 없다. 대신 JVM이 SIGQUIT(`kill -3`) 수신 시 표준 출력에 스레드 덤프를 쓰는 기능은 JRE에도 포함되어 있고, ENTRYPOINT가 `java -jar app.jar` 직접 실행이라 컨테이너 PID 1이 java 프로세스다 → `kubectl exec <pod> -- kill -3 1` + `kubectl logs <pod>`로 덤프 회수.
- `SlackNotificationService`는 incoming webhook + `response_url` 기반이라 Slack Web API의 `files.upload`(bot token, `files:write` 스코프 필요)를 쓸 수 없다. 따라서 전체 덤프를 그대로 보내는 대신, `BLOCKED` 상태인 스레드 블록만 추출해 Slack mrkdwn 섹션(3000자 제한)에 맞게 길이를 제한한다.
- 덤프 추출은 best-effort다. RBAC/타이밍 문제로 실패해도 `proposeRolloutRestart`의 핵심 가치(데드락 의심 시 재시작 승인 요청)는 그대로 동작해야 하므로, 예외를 던지지 않고 "스레드 덤프 추출 실패: {원인}" 문자열로 대체한다 (기존 `executeRolloutRestart` 등의 실패 처리 패턴과 동일).

## 검토했으나 채택하지 않은 대안

### 대안 A: Slack 3버튼([승인]/[스레드덤프 추출]/[거절]), 덤프 추출은 approvalId를 consume하지 않는 조회성 액션
- 무엇: 사용자가 덤프를 먼저 보고 재시작 여부를 판단할 수 있게 별도 버튼 제공.
- 왜 안 썼나: `ActionApprovalService.approve`/`reject`는 `PendingAction`을 맵에서 제거(consume)하는데, 덤프 추출 버튼이 같은 ID를 쓰면 이후 승인/거절이 동작하지 않는다. 이를 풀려면 `peek` 메서드 신설 + `SlackInteractiveController`에 신규 액션 타입 추가가 필요해 변경 범위가 3개 파일 이상으로 늘어난다. `proposeRolloutRestart` 자체가 "데드락 확신" 상태에서만 호출되므로, "덤프만 보고 재시작은 보류"하는 시나리오의 빈도가 낮다고 판단.

### 대안 B: "스레드덤프 추출 후 재시작"을 단일 combined 액션으로 추가
- 무엇: 기존 [승인]은 즉시 재시작, 새 버튼 [덤프 추출 후 재시작]은 덤프+재시작을 묶어서 실행.
- 왜 안 썼나: 대안 A보다는 단순하지만 여전히 신규 액션 타입/버튼/컨트롤러 분기가 필요하다. "항상 자동으로 덤프를 포함"하면 사용자가 어떤 버튼을 누르든 덤프가 이미 확보된 상태이므로, combined 액션이 주는 추가 가치가 없다.

### 대안 C: `kubectl exec`로 `jstack`/`jcmd` 직접 호출
- 무엇: JDK 진단 도구로 정형화된 스레드 덤프 추출.
- 왜 안 썼나: 베이스 이미지가 JRE-only(`eclipse-temurin:21-jre-jammy`)라 이 도구들이 컨테이너에 없다. `kill -3`은 JVM 자체 기능이라 베이스 이미지에 독립적이다.

## 기존 코드베이스 컨벤션
- `kubectl` 실행: `runKubectl(String... args)` (KubernetesTools.java:380) — `List<String> command` + `ProcessBuilder`, 비정상 종료 시 예외, 결과 없으면 "(결과 없음)" 반환.
- 외부 명령/API 실패 처리: try/catch로 감싸 "실패 메시지: {원인}" 문자열 반환 (예외를 던지지 않음) — 기존 `executeRolloutRestart` 등(ActionApprovalService.java:62)과 동일.
- Slack Block Kit 메시지: `buildXxxBlocks(id, ..., reason)` private 메서드가 `List<Map<String, Object>>` 반환, `slackService.sendBlockKit(fallbackText, blocks)`로 발송 (KubernetesTools.java:200-378).
- 테스트: 순수 로직(파싱/포맷팅)은 같은 패키지의 package-private 메서드로 분리해 단위 테스트, 외부 프로세스/HTTP 의존 로직은 기존 컨벤션상 테스트 없음.

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — `proposeRolloutRestart`(62), `buildRestartBlocks`(200), `runKubectl`(380)
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — `PendingAction` record, `approve`/`reject`의 consume 동작(45-60)
- `aiops/src/main/java/aiops/aiops/slack/SlackNotificationService.java` — `sendBlockKit`/`sendToResponseUrl`, webhook 기반(파일 업로드 불가)
- `serverA/Dockerfile`, `serverB/Dockerfile`, `serverC/Dockerfile` — `eclipse-temurin:21-jre-jammy`, `ENTRYPOINT ["java", ..., "-jar", "app.jar"]`
- `helm/promotion-app/templates/server-a/deployment.yaml` — 파드 라벨 `app: {{ .Values.serverA.name }}` (kubectl -l app=<deploymentName> 셀렉터에 사용)

## 외부 참조
- JVM SIGQUIT/Thread Dump: `kill -3 <pid>`를 받은 JVM은 `Full thread dump ...` 헤더와 함께 전체 스레드 스택을 표준출력에 기록 (HotSpot JVM 표준 동작, JDK/JRE 무관)

---

# 맥락 노트: 자동조치 완료 후 Linear 감사 티켓 자동 생성 (Part 1)

## 왜 이 방식을 선택했는가
- 외부 리뷰 Part 1은 "자동조치가 완료되면 무엇을 왜 했는지 감사 기록을 남겨라"는 요구였다. 이미 codebot에 Linear 연동(`LinearTools`, `linearClient`)이 구현되어 있으므로, 동일한 GraphQL `issueCreate` 패턴을 aiops에도 두는 것이 가장 빠르고 일관적이다.
- 라벨/담당자를 생략한 이유: codebot의 `createIssue`는 RCA(원인 분석) 이슈 전용으로 도메인(주문/결제/프로모션/유저)+직무(백엔드/프론트엔드/인프라) 라벨이 분류에 의미가 있다. 반면 aiops 감사 티켓은 "자동조치 실행 로그" 성격이라 이런 분류가 맞지 않고, 라벨 ID 조회를 위한 추가 GraphQL 호출(및 실패 지점)을 줄일 수 있다.
- 거절(`reject_*`)을 비범위로 둔 이유: 거절은 클러스터 상태를 변경하지 않으므로 "조치가 실행됐다"는 감사 가치가 없다. 포함하려면 `ActionApprovalService.reject()`가 `Optional<PendingAction>`을 반환하도록 시그니처를 바꿔야 하는데, 이는 이번 작업의 핵심(실행된 조치의 기록)과 무관한 변경 범위 확장이다.
- 성공/실패 모두 Slack에 알리는 이유: 묶음 C(스레드덤프)에서도 "best-effort 부가 기능의 실패를 사용자에게 짧게 알린다"는 패턴을 채택했다. 감사 티켓은 컴플라이언스 관점에서 "기록이 안 남았다"는 사실 자체도 사용자가 알아야 할 정보이므로 동일 패턴을 유지한다.
- Slack 알림을 "후속 메시지"가 아닌 "단일 합쳐진 메시지"로 보낸 이유: 계획서는 감사 티켓 결과를 후속 Slack 메시지로 전송한다고 표현했으나, `SlackNotificationService.sendToResponseUrl`이 보내는 body에는 `"replace_original": true`가 포함되어 있다. 즉 동일 `responseUrl`로 두 번째 메시지를 보내면 첫 번째 메시지(자동조치 실행 결과)가 화면에서 사라지고 감사 티켓 결과로 덮어써진다. 이를 피하기 위해 `SlackInteractiveController.sendResultWithAudit`에서 `executionResult + "\n\n" + auditResult`로 두 결과를 하나의 문자열로 합쳐 단일 호출로 전송한다 — `SlackNotificationService` 자체는 수정하지 않는다(Simplicity First).

## 검토했으나 채택하지 않은 대안

### 대안 A: codebot `LinearTools.createIssue` 재사용 (도메인/직무 라벨 자동 매핑)
- 무엇: `deployment`/`service` 파라미터 값(server-a/b/c 등)을 도메인 라벨(주문/결제/프로모션/유저)로 매핑해 기존 `createIssue`를 그대로 호출.
- 왜 안 썼나: 서비스명 → 도메인 라벨 매핑 규칙이 코드베이스 어디에도 정의되어 있지 않고, 액션 타입에 따라 `service`/`hpa`/`release`/`connector` 등 파라미터 키 자체가 달라 일관된 매핑 함수를 만들기 어렵다. "감사 로그"라는 목적에 비해 매핑 로직의 복잡도/실패 지점이 과하다.

### 대안 B: 거절(`reject_*`)도 감사 기록에 포함
- 무엇: `ActionApprovalService.reject(id)`가 `Optional<PendingAction>`을 반환하도록 바꿔, 거절 시에도 "AI가 제안 → 사람이 거절"을 Linear에 기록.
- 왜 안 썼나: `reject()`의 현재 반환형(`void`)을 바꾸면 호출부 시그니처 영향이 생기고, "감사"의 핵심 가치(실제 조치 실행 여부)에서 벗어난 범위 확장이다. 필요해지면 별도 작업으로 분리.

### 대안 C: 감사 기록을 DB 테이블/로그 파일로 적재
- 무엇: Linear 대신 자체 DB 테이블이나 구조화 로그로 감사 이력을 남긴다.
- 왜 안 썼나: 외부 리뷰 피드백이 명시적으로 "Linear/Jira 티켓"을 요구했고, 이미 Linear 연동 인프라(API 키, GraphQL 클라이언트 패턴)가 codebot에 존재해 재사용 비용이 가장 낮다.

## 기존 코드베이스 컨벤션
- `RestClient` 빈: `RestClientConfig`에서 `@Value`로 주입받은 URL/키로 `RestClient.builder()...build()` (codebot `RestClientConfig.linearClient`, `aiops/.../config/RestClientConfig.java`)
- GraphQL 호출: `ObjectMapper`로 `JsonNode` 파싱 후 `errors` 필드 체크 → `IllegalStateException` (`LinearTools.execute`)
- 외부 API 실패 처리: try/catch로 감싸 "실패: {원인}" 문자열 반환, 예외를 던지지 않음 (`LinearTools.createIssue`, `KubernetesTools.extractThreadDumpSummary`)
- 테스트: `MockRestServiceServer.bindTo(builder).build()`로 GraphQL POST 모킹 (`LinearToolsTest`)
- Slack 후속 메시지: `slackService.sendToResponseUrl(responseUrl, message)` — 동일 `responseUrl`로 여러 번 호출 가능 (`SlackInteractiveController`의 기존 6개 분기에서도 1회씩 사용 중)

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — `PendingAction` record(45-60), 6개 `executeXxx` 메서드(62-264)
- `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java` — 6개 `approve_*` 분기(processPayload)
- `aiops/src/main/java/aiops/aiops/config/RestClientConfig.java` — `linearClient` 빈 추가 위치
- `aiops/src/main/resources/application.yaml` — `linear:` 섹션 추가 위치 (codebot `application.yaml:42-44` 참고)
- `codebot/src/main/java/codebot/codebot/tools/LinearTools.java` — GraphQL 호출/에러 처리 템플릿
- `codebot/src/test/java/codebot/codebot/tools/LinearToolsTest.java` — 테스트 템플릿
- `docker-compose.override.yml`(gitignored) — aiops 컨테이너에 `LINEAR_API_KEY`/`LINEAR_TEAM_ID` 이미 주입됨
- `docker-compose.yml` 714-745행 — aiops 서비스 `environment` 블록 (`LINEAR_API_KEY`/`LINEAR_TEAM_ID` 추가 위치)

## 외부 참조
- Linear GraphQL API: `https://api.linear.app/graphql`, `issueCreate` mutation
- Slack `response_url`: 30분 내 최대 5회 재호출 허용 (Slack 공식 문서)
