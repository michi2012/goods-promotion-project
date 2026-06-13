# 계획서: codebot Pyroscope 핫스팟 도구 추가 + 단일파일 코드수정/PR 워크플로우 (묶음 B)

- 작성일: 2026-06-13
- 관련 이슈/티켓: 없음

## 목표
codebot이 (1) Pyroscope 프로파일링 핫스팟을 "운영 상태 확인" 조사 도구로 활용하고, (2) 사용자가 같은 Slack 스레드에서 명시적으로 코드 수정을 지시하면 단일 파일을 수정해 GitHub PR을 생성(또는 동일 이슈에 대한 기존 PR에 추가 커밋)할 수 있도록 한다.

## 성공 기준
- [ ] codebot ObservabilityTools에 aiops와 동일한 `queryProfilerHotspots`(+`extractHotspots`) 도구가 추가되고 `.\gradlew.bat :codebot:test` 통과
- [ ] CodebotAgentService SYSTEM_PROMPT "조사 원칙" 1단계 도구 목록에 `queryProfilerHotspots` 반영 확인
- [ ] `PullRequestTools.createFixPullRequest`가 (a) 신규 브랜치+PR 생성, (b) 기존 브랜치 추가 커밋(기존 PR 반환), (c) 브랜치는 있으나 open PR 없음(안내 메시지) 세 경로 모두 단위 테스트(Mock) 통과 — `.\gradlew.bat :codebot:test`
- [ ] CodebotAgentService에 `PullRequestTools` 통합 + "코드 수정" 프롬프트 섹션 추가 후 `.\gradlew.bat :codebot:build` 통과
- [ ] (구현 완료 후, 사용자가 직접) Slack에서 "고쳐서 PR 올려줘" 1회 트리거하여 실제 GitHub 브랜치/PR 생성 동작 확인

## 비범위 (Out of Scope)
- CI 빌드/테스트 검증 루프 (`.github/workflows` 자체가 존재하지 않음)
- 멀티파일 수정을 위한 샌드박스/git-clone 기반 아키텍처 (Devin/Sweep 스타일, 묶음 C로 분리)
- ChatMemory의 Redis 영속화 (묶음 A 비범위 유지)
- PR이 머지/닫힌 후 동일 이슈로 재요청하는 케이스의 정교한 처리 — "브랜치 존재 + open PR 없음"은 안내 메시지로만 처리하고 그 이상 분기하지 않음
- 실제 GitHub 레포에 브랜치/PR을 생성하는 자동 E2E (공유 저장소 상태 변경이므로 사용자가 최종 1회 수동 트리거로 확인)

## 단계별 작업 계획

### 단계 1: codebot Pyroscope 핫스팟 도구 추가
- 변경 파일:
  - `codebot/src/main/java/codebot/codebot/config/RestClientConfig.java`
  - `codebot/src/main/resources/application.yaml`
  - `codebot/src/test/resources/application.yaml`
  - `codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java`
  - `codebot/src/test/java/codebot/codebot/tools/ObservabilityToolsTest.java`
- 변경 내용 요약: aiops의 `pyroscopeClient` 빈(+`observability.pyroscope.url` 설정, 기본값 `http://pyroscope:4040`)과 `queryProfilerHotspots`/`extractHotspots`(flamebearer JSON을 self CPU 시간 기준으로 파싱해 Top N 메서드 추출)를 codebot ObservabilityTools에 동일 패턴으로 복제. 생성자에 4번째 RestClient(`pyroscopeClient`) 추가.
- 검증 방법: `.\gradlew.bat :codebot:test`
- 롤백 방법: 위 5개 파일 변경 git revert
- 예상 소요: 짧음

### 단계 2: CodebotAgentService 조사 원칙에 Pyroscope 반영
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용 요약: SYSTEM_PROMPT "조사 원칙" 1단계(운영 상태부터 확인) 도구 목록에 `queryProfilerHotspots` 추가.
- 검증 방법: `.\gradlew.bat :codebot:test`
- 롤백 방법: 해당 라인 git revert
- 예상 소요: 짧음

> 단계 1-2는 낮은 리스크(테스트 코드 포함 단순 추가)로 묶음 실행 가능.

### 단계 3: PullRequestTools 신규 클래스 — 단일파일 수정 + PR 생성/추가커밋
- 변경 파일:
  - `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java` (신규)
  - `codebot/src/test/java/codebot/codebot/tools/PullRequestToolsTest.java` (신규)
- 변경 내용 요약:
  - `createFixPullRequest(filePath, newContent, issueIdentifier, commitMessage, prTitle, prBody)` 단일 도구.
  - 브랜치명은 `feature/{issueIdentifier 소문자}-codebot-fix`로 결정적 생성 (예: `MIC-12` → `feature/mic-12-codebot-fix`).
  - 흐름: ① `GET /git/refs/heads/{branch}`로 브랜치 존재 확인 → ② 없으면 main SHA 조회 후 `POST /git/refs`로 브랜치 생성, 있으면 스킵 → ③ 해당 브랜치에서 `GET /contents/{path}?ref={branch}`로 파일 SHA 조회 → ④ `PUT /contents/{path}`로 base64 인코딩된 새 내용 커밋 → ⑤ 신규 브랜치였으면 `POST /pulls`로 PR 생성(본문에 `Closes {issueIdentifier}` + 고정 안내문 자동 추가) 후 PR URL 반환, 기존 브랜치였으면 `GET /pulls?head={owner}:{branch}&state=open`으로 기존 PR URL을 찾아 "추가 커밋 반영" 메시지로 반환 (못 찾으면 안내 메시지).
  - GitHub API 실패는 기존 도구들과 동일하게 try/catch 후 실패 메시지 반환.
- 검증 방법: `.\gradlew.bat :codebot:test` (MockRestServiceServer로 3가지 경로 — 신규 브랜치/PR, 기존 브랜치 추가커밋, 브랜치만 있고 open PR 없음 — 모두 검증)
- 롤백 방법: 신규 파일 2개 삭제
- 예상 소요: 보통

### 단계 4: CodebotAgentService에 PullRequestTools 통합 + "코드 수정" 프롬프트 섹션
- 변경 파일: `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`
- 변경 내용 요약:
  - 생성자에 `PullRequestTools` 주입, `tools(...)` 목록에 추가.
  - SYSTEM_PROMPT에 "## 코드 수정 (createFixPullRequest)" 섹션 추가 — 트리거 조건(사용자의 명시적 지시), 단일 파일 한정(여러 파일 필요 시 호출하지 않고 안내), `issueIdentifier`는 이전 대화에서 생성한 Linear 이슈 식별자 사용, prBody 작성 가이드(변경 요약/테스트 방법/영향도).
- 검증 방법: `.\gradlew.bat :codebot:build`
- 롤백 방법: git revert
- 예상 소요: 짧음

### 단계 5: createFixPullRequest 보호 경로 가드 추가
- 변경 파일:
  - `codebot/src/main/java/codebot/codebot/tools/PullRequestTools.java`
  - `codebot/src/test/java/codebot/codebot/tools/PullRequestToolsTest.java`
- 변경 내용 요약: `createFixPullRequest` 호출 시작 시 `filePath`가 `.github/`(CI/CD 워크플로우) 또는 `.env`/`.env.*`(환경변수) 경로면 GitHub API 호출 없이 즉시 안내 메시지를 반환한다. LLM의 잘못된 판단으로 CI 설정이나 비밀 관련 파일이 수정 대상이 되는 것을 1차로 차단한다 (PR 리뷰는 2차 방어선).
- 검증 방법: `.\gradlew.bat :codebot:test` (보호 경로 호출 시 GitHub API를 호출하지 않고 안내 메시지를 반환하는지 테스트)
- 롤백 방법: 추가한 가드 메서드/분기 및 테스트 제거
- 예상 소요: 짧음

> 단계 5는 단계 3(PullRequestTools)에 대한 낮은 리스크 보강으로, 단계 3-4와 함께 커밋 가능.

## 리스크 및 대응
- LLM이 `newContent`(파일 전체 내용)를 잘못 생성해 기존 코드를 손상시킬 위험 → PR은 자동 머지하지 않으므로 사람이 diff 리뷰 후 머지. 고정 안내문("⚠️ codebot이 자동 생성한 PR입니다. 머지 전 리뷰·테스트 필요")을 PR 본문에 항상 추가.
- 결정적 브랜치명으로 인해, 동일 이슈에 대해 사용자가 "이전과 다른 방향"으로 재수정을 요청해도 같은 브랜치/PR에 누적됨 → PR diff에서 누적된 변경을 사람이 확인. "처음부터 다시" 시나리오는 비범위.
- GitHub API rate limit/일시 오류 → try/catch로 실패 메시지를 사용자에게 그대로 안내, 자동 재시도 없음 (기존 도구들과 동일 정책).

## 의존성
- aiops `ObservabilityTools.queryProfilerHotspots`/`extractHotspots` 구현 (복제 대상)
- codebot `githubClient` (GITHUB_TOKEN, repo write scope — 묶음 A에서 이미 구성·주입됨)
- `.claude/commands/pr.md`의 `Closes MIC-{n}` 컨벤션 (Linear 자동 상태 전환, 신규 코드 불필요)

---

# 계획서: 데드락 의심 롤링 재시작 시 스레드 덤프 자동 확보 (묶음 C)

- 작성일: 2026-06-13
- 관련 이슈/티켓: 없음

## 목표
aiops가 데드락 의심으로 롤링 재시작을 제안(`proposeRolloutRestart`)할 때, 재시작으로 사라질 스레드 상태를 사전에 확보해 BLOCKED 스레드 정보를 Slack 승인 메시지에 포함한다.

## 성공 기준
- [ ] `proposeRolloutRestart` 호출 시 대상 파드에서 스레드 덤프를 추출하고, BLOCKED 스레드 블록을 Slack 승인 메시지(`buildRestartBlocks`)에 포함한다.
- [ ] 덤프 추출이 실패해도 롤링 재시작 제안 자체는 정상 진행된다 (best-effort, 실패 메시지로 대체).
- [ ] BLOCKED 스레드 파싱/길이 제한 로직(`extractBlockedThreads`)에 대한 단위 테스트 작성 및 `.\gradlew.bat :aiops:test` 통과.
- [ ] `.\gradlew.bat :aiops:build` 통과.

## 비범위 (Out of Scope)
- Part 1: 자동조치 완료 후 Linear/Jira 티켓 자동 생성 (별도 작업)
- Part 2-2: HPA Max + 클러스터 여유자원 컨텍스트 제공 (별도 작업)
- Part 2-3: Istio 카나리 격리 에러율 기반 롤백 (별도 작업)
- 덤프 결과의 영구 저장(S3 등) — Slack 메시지로 회수하는 것까지만
- Slack 버튼 기반 "스레드덤프 추출" 옵션 (자동 포함 방식으로 대체)
- `kubectl exec`/`logs` 연계 로직(`extractThreadDumpSummary`) 자체에 대한 단위 테스트 (기존 `executeRolloutRestart` 등과 동일하게 ProcessBuilder 의존 로직은 테스트 없음 — 파싱 로직만 분리하여 테스트)

## 단계별 작업 계획

### 단계 1: BLOCKED 스레드 파싱/요약 헬퍼 추가 + 단위 테스트
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
  - `aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java` (신규)
- 변경 내용 요약: 스레드 덤프 텍스트(`String`)를 입력받아, `"`로 시작하는 스레드 블록 중 `BLOCKED`가 포함된 블록만 추출하고, 결과가 2500자를 넘으면 잘라내는 package-private 순수 함수 `extractBlockedThreads(String dump)`를 추가한다. BLOCKED 스레드가 없으면 "BLOCKED 상태 스레드 없음"을 반환한다. 같은 패키지의 테스트 클래스에서 직접 호출 가능하도록 접근제어자를 package-private으로 둔다.
- 검증 방법: `.\gradlew.bat :aiops:test` — 샘플 스레드 덤프 텍스트(BLOCKED 포함/미포함/길이초과)로 `extractBlockedThreads` 단위 테스트
- 롤백 방법: 추가한 메서드 및 테스트 파일 제거
- 예상 소요: 짧음

### 단계 2: 스레드 덤프 추출(kubectl 연계) + proposeRolloutRestart/buildRestartBlocks 통합
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용 요약:
  - private 헬퍼 `extractThreadDumpSummary(String deploymentName)` 추가: ① `kubectl get pods -n {ns} -l app={deployment} -o jsonpath={.items[0].metadata.name}`로 첫 파드명 조회 → ② `kubectl exec {pod} -n {ns} -- kill -3 1`로 SIGQUIT 전송 → ③ 약간의 지연 후 `kubectl logs {pod} -n {ns} --tail=500`로 출력 회수 → ④ `extractBlockedThreads`로 요약. 과정 중 예외 발생 시 "스레드 덤프 추출 실패: {원인}" 반환 (예외를 던지지 않음).
  - `proposeRolloutRestart`에서 Slack 발송 전에 `extractThreadDumpSummary(deploymentName)`를 호출해 결과를 `buildRestartBlocks`에 전달.
  - `buildRestartBlocks(id, deployment, reason, threadDumpSummary)`로 시그니처 변경 — section mrkdwn에 "*스레드 덤프 (BLOCKED 스레드):*\n```{summary}```" 블록 추가.
- 검증 방법: `.\gradlew.bat :aiops:build` (kubectl 연계 부분은 컴파일/빌드까지만 — 로컬에 kubectl 클러스터 접근이 있다면 수동으로 `proposeRolloutRestart` 호출 결과 Slack 메시지 형태 확인)
- 롤백 방법: 시그니처 변경 및 추가 메서드 git revert
- 예상 소요: 보통

> 단계 1-2는 모두 낮은 리스크(같은 파일 내 헬퍼 추가 + 시그니처 변경, git으로 쉽게 되돌릴 수 있음)로 묶음 실행 가능.

## 리스크 및 대응
- `kubectl exec ... -- kill -3 1`이 RBAC 권한 문제로 실패할 수 있음 → best-effort 처리로 재시작 제안 자체는 막지 않음. 로컬 kubectl은 cluster-admin이라 RBAC 미검증 — EKS 배포 후 별도 확인 필요.
- SIGQUIT 전송 후 JVM이 스레드 덤프를 stdout에 쓰기까지 지연이 있을 수 있음 → `kubectl exec`와 `kubectl logs` 사이에 짧은 대기를 둔다. 그래도 타이밍이 안 맞으면 "BLOCKED 상태 스레드 없음" 또는 빈 결과가 반환될 수 있음 — best-effort이므로 재시작 제안에는 영향 없음.
- `ProcessBuilder` 기반 로직은 기존 `runKubectl`/`executeRolloutRestart`와 동일하게 `List<String> command`로 구성 (CLAUDE.md 커맨드 인젝션 규칙 준수, `deploymentName`/`namespace`는 LLM tool 파라미터 — 기존과 동일 신뢰 수준).

## 의존성
- 기존 `proposeRolloutRestart`/`buildRestartBlocks`/`runKubectl` (KubernetesTools.java:62, 200, 380)

---

# 계획서: 자동조치 완료 후 Linear 감사 티켓 자동 생성 (Part 1)

- 작성일: 2026-06-13
- 관련 이슈/티켓: 없음

## 목표
aiops가 Slack 승인을 받아 자동조치(롤링재시작/HPA패치/Helm롤백/트래픽시프트/Outlier업데이트/Debezium재시작)를 실행하면, "무엇을·왜·결과가 어땠는지"를 Linear 티켓으로 자동 기록해 감사(audit) 흔적을 남긴다.

## 성공 기준
- [ ] aiops에 `linear` 설정(`application.yaml`)과 `linearClient` RestClient 빈이 추가되고 `.\gradlew.bat :aiops:build` 통과
- [ ] `LinearAuditService.createAuditTicket(...)`이 라벨/담당자 없이 teamId+title+description만으로 Linear 이슈를 생성하며, 성공/실패 모두 문자열로 반환(예외 없음) — `LinearAuditServiceTest`(MockRestServiceServer) 통과
- [ ] `SlackInteractiveController`의 6개 `approve_*` 분기 모두에서 `executeXxx` 직후 `createAuditTicket` 호출 후 결과를 Slack에 후속 메시지로 전송 (성공/실패 모두)
- [ ] `docker-compose.yml`의 aiops `environment`에 `LINEAR_API_KEY`/`LINEAR_TEAM_ID` 참조 추가
- [ ] `.\gradlew.bat :aiops:test` 통과

## 비범위 (Out of Scope)
- 거절(`reject_*`) 액션에 대한 감사 기록 — `ActionApprovalService.reject()` 시그니처 변경 필요, 별도 작업
- Linear 라벨/담당자 자동 분류 (도메인/직무 라벨)
- Jira 연동
- 2번(HPA Max + 클러스터 여유자원 컨텍스트), 3번(Istio 카나리 격리 에러율) — 별도 작업
- `SlackInteractiveController` 컨트롤러 레벨 단위 테스트 (기존 컨벤션상 없음, 유지)

## 단계별 작업 계획

### 단계 1: aiops Linear 클라이언트 설정 추가
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/config/RestClientConfig.java`
  - `aiops/src/main/resources/application.yaml`
- 변경 내용 요약: codebot의 `linearClient` 빈(baseUrl=`https://api.linear.app/graphql`, `Authorization` 헤더=api-key, `Content-Type: application/json`)을 aiops `RestClientConfig`에 동일하게 추가. `application.yaml`에 `linear.api-key: ${LINEAR_API_KEY:}` / `linear.team-id: ${LINEAR_TEAM_ID:}` 섹션 추가.
- 검증 방법: `.\gradlew.bat :aiops:build`
- 롤백 방법: 추가한 빈/설정 섹션 git revert
- 예상 소요: 짧음

### 단계 2: LinearAuditService 신규 클래스 + 단위 테스트
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/linear/LinearAuditService.java` (신규)
  - `aiops/src/test/java/aiops/aiops/linear/LinearAuditServiceTest.java` (신규)
- 변경 내용 요약: `createAuditTicket(String actionType, String paramsJson, String reason, String executionResult)` — Linear GraphQL `issueCreate` mutation을 teamId+title+description만으로 호출(라벨/담당자 없음, GraphQL 1회). 제목은 `[감사] {actionType} 자동조치 실행`, 본문에 파라미터/제안 사유/실행 결과/시각을 포함. 성공 시 "Linear 감사 티켓 생성 완료: {identifier} — {url}", 실패(HTTP 오류/GraphQL errors/success=false) 시 "Linear 감사 티켓 생성 실패: {원인}" 반환 — 예외를 던지지 않음 (`LinearTools.createIssue`와 동일 패턴).
- 검증 방법: `.\gradlew.bat :aiops:test` — `LinearToolsTest` 패턴으로 성공/GraphQL 오류/HTTP 오류 3케이스
- 롤백 방법: 신규 파일 2개 삭제
- 예상 소요: 보통

### 단계 3: SlackInteractiveController 통합 (6개 분기)
- 변경 파일: `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java`
- 변경 내용 요약: 생성자에 `LinearAuditService` 주입. 6개 `approve_*` 분기 각각에서 `executeXxx(pending)` 결과를 `sendToResponseUrl`로 보낸 뒤, `createAuditTicket(pending.actionType(), pending.params(), pending.reason(), result)`를 호출해 결과(성공/실패)를 추가 Slack 메시지로 전송.
- 검증 방법: `.\gradlew.bat :aiops:build`
- 롤백 방법: git revert

> 단계 1-3은 낮은 리스크(같은 파일 내 추가/시그니처 영향 없음, git으로 쉽게 되돌릴 수 있음)로 묶음 실행 가능.

### 단계 4: docker-compose.yml 설정 추가
- 변경 파일: `docker-compose.yml`
- 변경 내용 요약: aiops `environment` 블록에 `LINEAR_API_KEY: ${LINEAR_API_KEY}`, `LINEAR_TEAM_ID: ${LINEAR_TEAM_ID}` 두 줄 추가 (`GITHUB_OWNER` 등과 동일한 `${VAR}` 참조 패턴).
- 검증 방법: 육안 확인 (`docker compose config` 가능하면 파싱 확인)
- 롤백 방법: 추가한 두 줄 제거
- 예상 소요: 짧음

> 단계 4는 단순 환경변수 참조 추가로, 단계 1-3과 함께 커밋 가능.

## 리스크 및 대응
- Linear API 호출 실패(네트워크/인증) → best-effort, 실패 메시지를 반환하고 Slack에도 알림(자동조치 자체의 성패에는 영향 없음).
- 감사 티켓 본문에 `params`(JSON)를 그대로 포함 — `params`는 deployment/hpa/release/service 등 비민감 식별자만 포함(`ActionApprovalService`의 6개 propose 호출부 확인 결과 시크릿 없음), 노출 위험 없음.
- Slack `response_url` 추가 호출(실행 결과 + 감사 티켓 결과, 총 2회) — Slack은 `response_url`당 30분 내 최대 5회 재호출을 허용하므로 문제 없음.

## 의존성
- 기존 `ActionApprovalService`의 6개 `executeXxx` 메서드 및 `PendingAction` record
- codebot `LinearTools`/`RestClientConfig.linearClient` (참고 템플릿, 직접 재사용 아님 — aiops에 동일 패턴으로 신규 작성)
- `docker-compose.override.yml`(gitignored)에 이미 `LINEAR_API_KEY`/`LINEAR_TEAM_ID`가 aiops 컨테이너에 주입되어 있음 (로컬 테스트 준비 완료)
