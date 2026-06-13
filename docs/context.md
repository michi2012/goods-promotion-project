# 맥락 노트: Router-Worker 챗봇 — aiops 인프라 라우터 + codebot 코드조사/Linear 티켓 생성 (묶음 A)

## 왜 이 방식을 선택했는가
- aiops는 현재 "Prometheus 알람 → 1회성 분석 → Slack 보고 → (인프라 조작은) 제안/승인/실행"까지 수행하지만, "개발자가 Slack에서 의심 문제를 던지면 조사 후 Linear 이슈를 생성"하는 흐름은 없었다.
- 두 흐름(인프라 자동 대응 vs 개발자 발화 기반 코드 조사)을 하나의 거대한 에이전트에 합치기보다, **Router-Worker(Orchestrator/Supervisor) 패턴**으로 분리하기로 합의했다 — 라우터가 의도(인프라/코드)를 분류해 적절한 워커에게 위임.
- 모듈 분리 기준은 **credential 격리**다: codebot은 GitHub code search + Linear issue 생성처럼 더 위험하거나 외부 노출 범위가 다른 자격증명을 가지므로, aiops와 별도의 Gradle 모듈/배포 단위(`codebot`)로 분리한다. 반면 라우터(의도 분류 + 인프라 분기)는 기존 aiops의 자격증명·도구를 그대로 쓰므로 aiops 내부 신규 패키지(`router`)로 둔다.
- "Phase 1(라우터+인프라)/Phase 2(codebot)로 나누자"는 제안에 대해 사용자가 "현업이라면 정확히 이렇게 구현했는지"를 재질문했고, 이에 대한 보강으로 다음 2가지를 이번 plan에 포함하기로 했다:
  1. **멀티턴 대화 컨텍스트(ChatMemory)** — Slack 스레드(`thread_ts`)를 `conversationId`로 사용해 후속 질문에 이전 맥락을 반영.
  2. **라우터의 구조화된 분류 호출** — 메인 에이전트 호출 전, 가벼운 LLM 호출로 `INFRA/CODE/UNKNOWN`을 구조화 출력으로 결정해 분기.
- 동시에 제안했던 3번째(ObservabilityTools를 aiops/codebot 공유 Gradle 모듈로 추출)는 사용자가 명시적으로 **제외**했다 — codebot은 "트레이싱/리소스/로그 필요시 조회"를 자체 패키지에 별도 구현(복제)한다.
- "Phase 분리"는 사용자가 재차 거부("한번에 제대로 구현하자")했으므로, 단일 plan으로 진행하되 단계 순서를 의존성 순(codebot 우선 구축 → aiops 라우터 → 연동/E2E)으로 배치해 단계별 검증이 가능하도록 구성했다.

## 검토했으나 채택하지 않은 대안

### 대안 A: ObservabilityTools를 공유 Gradle 모듈로 추출
- 무엇: `aiops/.../tools/ObservabilityTools.java`를 별도 모듈(예: `observability-tools`)로 추출해 aiops/codebot이 의존성으로 공유.
- 왜 안 썼나: 사용자가 이번 범위에서 명시적으로 제외 지시. codebot은 필요한 조회(Loki 로그/Tempo 트레이스/Prometheus 메트릭 서브셋 + 읽기전용 K8s 상태)만 자체 패키지에 복제 구현한다. 코드 중복은 발생하지만 모듈 간 결합도를 낮추고 이번 plan의 범위를 좁힌다. 향후 중복이 부담되면 별도 작업으로 추출 검토.

### 대안 B: Phase 1(라우터+인프라 챗봇) / Phase 2(codebot) 분리
- 무엇: 먼저 라우터+인프라 분기만 구현해 배포·검증한 뒤, codebot+Linear 연동을 후속 plan으로 분리.
- 왜 안 썼나: 사용자가 "현업이라면 정확히 이렇게 구현했는지" 재질문 후 "한번에 제대로 구현하자"고 명시적으로 요청. 단일 plan으로 진행하되, 단계 순서를 codebot(1~4) → aiops 라우터(5) → 연동/E2E(6) → Helm(7)으로 배치해 의존성과 검증 가능성을 확보했다.

### 대안 C: Redis 기반 ChatMemory (`spring-ai-starter-model-chat-memory-redis`)
- 무엇: ChatMemory를 Redis에 영속화해 pod 재시작 후에도 대화 컨텍스트 유지.
- 왜 안 썼나: 신규 Gradle 의존성 추가가 필요해 사용자 사전 확인 대상이 된다. 이번 범위에서는 in-memory `MessageWindowChatMemory`로 충분하다고 판단했고, 신규 의존성 없이 진행한다. pod 재시작 시 컨텍스트 소실은 알려진 제약으로 남긴다.

### 대안 D: `@LoadBalanced RestClient`(spring-cloud-starter-loadbalancer) 기반 서비스 디스커버리
- 무엇: aiops 라우터가 `lb://codebot` 형태의 URI로 codebot을 호출.
- 왜 안 썼나: `spring-cloud-starter-loadbalancer`라는 신규 의존성이 필요하다. 대신 aiops가 이미 가진 `spring-cloud-starter-netflix-eureka-client`에 포함된 `DiscoveryClient`로 `codebot` 인스턴스를 조회해 일반 `RestClient`로 호출한다. codebot은 replicas=1이므로 로드밸런싱이 불필요하다.

### 대안 E: codebot이 자체 Slack Bot Token으로 직접 메시지 게시
- 무엇: codebot이 조사 완료 후 Slack `chat.postMessage`를 직접 호출해 알림+링크를 보낸다.
- 왜 안 썼나: credential 격리(아이덴티티 분리) 원칙에 따라, Slack 메시징은 aiops 라우터(`SlackBotClient`)만 담당한다. codebot은 내부 API 응답(텍스트)만 반환하고, aiops가 이를 같은 스레드에 게시한다. codebot이 보유하는 자격증명은 GitHub PAT(code search)와 Linear API Key로 한정된다.

## 기존 코드베이스 컨벤션
- 디렉토리 구조: `aiops/src/main/java/aiops/aiops/{agent,tools,slack,approval,webhook,config}`. 신규 `router` 패키지도 동일 레벨(`aiops.aiops.router`)에 추가한다. codebot은 동일 패턴으로 `codebot/src/main/java/codebot/codebot/{agent,tools,api,config}`.
- RestClient 빈 패턴: `aiops/.../config/RestClientConfig.java`의 `buildWithTimeout(baseUrl)`(connect/read timeout 3초) 헬퍼를 codebot의 `RestClientConfig`에서도 동일하게 차용한다. `slackClient`/`githubClient`는 각각 5초/기본 타임아웃 패턴을 따른다.
- Tool 어노테이션: `@Tool(description = """...""")` + `@ToolParam` — "언제 호출", "반환", "실패 시" 구조의 description 작성 스타일을 `ObservabilityTools`/`KubernetesTools`에서 그대로 따른다.
- 시스템 프롬프트 스타일: `AiOpsAgentService`의 `SYSTEM_PROMPT`는 1~10단계 순서가 고정된 "스크립트형"이다. 신규 `InfraChatAgentService`/`CodebotAgentService`는 멀티턴 대화에 맞춰 원칙·가이드라인을 제시하고 LLM이 ReAct로 도구를 선택하는 "가이드라인형" 프롬프트로 작성한다(논의 중 합의된 차이).
- Linear 필드 매핑: `.claude/commands/spec-to-tickets.md`의 `team: MIC`, `assignee: me`, 도메인+직무 라벨, description 마지막의 "## PR 전 체크리스트" 고정 섹션을 `LinearTools.createIssue`가 동일하게 채운다. 원인 분석 섹션은 `.claude/commands/incident.md`의 RCA 구조(증상/근본원인 — "가설:" 표기 포함)를 차용한다.
- 테스트 구조: `aiops/src/test/java/aiops/aiops/AiopsApplicationTests.java`(`@SpringBootTest` + `contextLoads`) 및 `aiops/src/test/resources/application.yaml`(테스트용 placeholder 설정) 패턴을 codebot에도 동일하게 둔다. 단위 테스트 작성 시 `.claude/skills/testing` 매뉴얼 참고.

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — 기존 1회성 알람 분석 에이전트, 신규 챗봇과 프롬프트 스타일 대비 참고
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java`, `KubernetesTools.java` — InfraChatAgentService가 그대로 재사용
- `aiops/src/main/java/aiops/aiops/slack/SlackNotificationService.java` — 기존 Incoming Webhook 알림(변경 없음). 신규 `SlackBotClient`(Bot Token, `chat.postMessage`)는 별도 클래스로 추가
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — 인프라 조작 승인 패턴(이번 작업에서는 변경 없음, codebot의 Linear 이슈 생성은 승인 없이 즉시 처리하는 점과 대비)
- `aiops/src/main/java/aiops/aiops/config/RestClientConfig.java` — `buildWithTimeout` 패턴의 출처
- `aiops/build.gradle`, `settings.gradle` — codebot 모듈 스캐폴딩 템플릿
- `helm/promotion-app/templates/aiops/{deployment,service,rbac}.yaml`, `helm/promotion-app/values.yaml`(`aiops:` 블록) — codebot Helm 템플릿 템플릿

## 외부 참조
- `.claude/commands/spec-to-tickets.md` — Linear 이슈 필드 매핑(team/assignee/labels/description 구조, PR 전 체크리스트)
- `.claude/commands/incident.md` — RCA 문서 구조(증상/타임라인/근본원인/재발방지, severity 기준)
- `.claude/commands/plan.md` — 본 작업이 따르는 `/plan` 프로토콜
- Slack Events API(`app_mention`, URL verification challenge) 및 Web API `chat.postMessage` — `SLACK_BOT_TOKEN` 필요
- Linear GraphQL API(`https://api.linear.app/graphql`) — `issueCreate` 뮤테이션, 팀/라벨 ID 조회
