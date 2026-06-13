# 계획서: Router-Worker 챗봇 — aiops 인프라 라우터 + codebot 코드조사/Linear 티켓 생성 (묶음 A)

- 작성일: 2026-06-13
- 관련 이슈/티켓: 없음

## 목표
Slack 앱 멘션을 받으면 의도(인프라 vs 코드/애플리케이션)를 분류해, 인프라 문제는 aiops가 기존 도구로 직접 응답하고, 코드/애플리케이션 문제는 신규 `codebot` 모듈이 조사 후 Linear 이슈(RCA 포함)를 생성해 알림+링크를 반환한다. 양쪽 모두 Slack 스레드 기반 멀티턴(ChatMemory)을 지원한다.

## 성공 기준
- [ ] `.\gradlew.bat build` 전체 모듈(codebot 포함) 빌드 및 테스트 통과
- [ ] `helm template helm\promotion-app` 렌더링 성공 (codebot 템플릿 포함, 기존 차트 비파괴)
- [ ] 로컬 E2E: discovery-service + aiops + codebot 기동 후 `/slack/events`에 INFRA 의도 app_mention 이벤트를 보내면 InfraChatAgentService가 응답하고, 같은 스레드의 후속 질문에서 이전 컨텍스트(ChatMemory)를 반영함
- [ ] 로컬 E2E: `/slack/events`에 CODE 의도 app_mention 이벤트를 보내면 라우터가 codebot에 위임 → codebot이 Linear 이슈를 생성하고 알림+링크 텍스트를 반환함
- [ ] 신규 단위 테스트(IntentClassifierService 분류 분기, LinearTools 요청 페이로드, CodeSearchTools/ObservabilityTools 응답 파싱) 통과

## 비범위 (Out of Scope)
- 코드 수정/PR 생성/빌드-테스트 검증 루프 (묶음 B로 분리)
- Linear 이슈 상태의 PR 생명주기 자동 업데이트
- `ObservabilityTools`를 aiops/codebot 공유 Gradle 모듈로 추출 (codebot은 자체 패키지에 필요한 조회만 복제 구현 — 의도적 중복 허용)
- Redis 기반 ChatMemory (in-memory `MessageWindowChatMemory`로 시작, pod 재시작 시 컨텍스트 소실은 알려진 제약)
- Slack App 설정 자체(Bot Token 발급, Event Subscriptions 등록, `app_mentions:read`/`chat:write` 스코프 부여) — Slack 워크스페이스에서 사용자가 직접 수행
- Linear 워크스페이스의 팀 ID/라벨 ID 확인 — 1회성 설정값으로 사용자 제공 필요
- EKS 실제 배포/RBAC 검증 (`helm template` 렌더링까지만)
- codebot 다중 replica/로드밸런싱 (replicas=1, `DiscoveryClient` 단일 인스턴스 조회로 충분)

## 단계별 작업 계획 (최대 7단계)

### 단계 1: codebot 모듈 스캐폴딩
- 변경 파일: `settings.gradle`, `codebot/build.gradle`(신규), `codebot/src/main/java/codebot/codebot/CodebotApplication.java`(신규), `codebot/src/main/resources/application.yaml`(신규), `codebot/src/test/java/codebot/codebot/CodebotApplicationTests.java`(신규), `codebot/src/test/resources/application.yaml`(신규)
- 변경 내용 요약: `settings.gradle`에 `codebot` 모듈을 추가하고, aiops의 `build.gradle`과 동일한 의존성 세트(eureka-client, web, validation, lombok, spring-ai-starter-model-google-genai, actuator, micrometer, otlp 등 — 신규 라이브러리 도입 없음)로 `codebot/build.gradle`을 작성한다. 기본 `CodebotApplication`과 `application.yaml`(포트 8086, Eureka, AI 설정)을 구성한다.
- 검증 방법: `.\gradlew.bat :codebot:build`
- 롤백 방법: `settings.gradle`의 `codebot` 추가분 제거, `codebot/` 디렉토리 삭제
- 예상 소요: 보통

### 단계 2: codebot 도구 — 코드 검색 + 자체 관찰성/리소스 조회
- 변경 파일: `codebot/src/main/java/codebot/codebot/config/RestClientConfig.java`(신규), `codebot/src/main/java/codebot/codebot/tools/CodeSearchTools.java`(신규), `codebot/src/main/java/codebot/codebot/tools/ObservabilityTools.java`(신규), `codebot/src/main/java/codebot/codebot/tools/KubernetesTools.java`(신규), `codebot/src/main/resources/application.yaml`(수정), 대응 테스트 3종(`src/test/java/codebot/codebot/tools/`)
- 변경 내용 요약: GitHub Code Search API로 코드를 검색하는 `CodeSearchTools`, 그리고 "필요시" 조회용으로 Loki 로그/Tempo 트레이스/Prometheus 메트릭 서브셋을 조회하는 codebot 자체 `ObservabilityTools`(aiops 것과 별개 구현)와 읽기 전용 `getClusterStatus`만 제공하는 `KubernetesTools`를 추가한다. `RestClientConfig`에 `githubClient`/`lokiClient`/`tempoClient`/`prometheusClient` 빈을 정의한다(aiops의 `buildWithTimeout` 패턴 차용).
- 검증 방법: `.\gradlew.bat :codebot:test` (각 Tool의 RestClient 응답 파싱 단위 테스트)
- 롤백 방법: 해당 신규 파일 삭제, `application.yaml` 추가분 제거
- 예상 소요: 김

### 단계 3: codebot 도구 — Linear 티켓 생성
- 변경 파일: `codebot/src/main/java/codebot/codebot/config/RestClientConfig.java`(수정 — `linearClient` 추가), `codebot/src/main/java/codebot/codebot/tools/LinearTools.java`(신규), `codebot/src/main/resources/application.yaml`(수정 — `linear.api-key`, `linear.team-id`), 대응 테스트(`LinearToolsTest.java`)
- 변경 내용 요약: Linear GraphQL API(`https://api.linear.app/graphql`)에 `issueCreate` 뮤테이션을 보내는 `LinearTools.createIssue` 도구를 추가한다. `/spec-to-tickets` 컨벤션(team=MIC, assignee=me, 도메인+직무 라벨, "PR 전 체크리스트" 고정 섹션)과 `/incident`의 RCA 구조(증상/원인 가설/근거)를 description에 반영한다. 팀 ID는 설정값(`LINEAR_TEAM_ID`)으로 주입하고, 라벨은 이름→ID를 런타임에 조회해 매핑한다.
- 검증 방법: `.\gradlew.bat :codebot:test` (mock `linearClient`로 GraphQL 요청 페이로드/응답 파싱 검증)
- 롤백 방법: 해당 신규 파일 삭제, `RestClientConfig`/`application.yaml` 추가분 제거
- 예상 소요: 보통

### 단계 4: codebot 에이전트 + 내부 API
- 변경 파일: `codebot/src/main/java/codebot/codebot/config/ChatMemoryConfig.java`(신규), `codebot/src/main/java/codebot/codebot/agent/CodebotAgentService.java`(신규), `codebot/src/main/java/codebot/codebot/api/InvestigationController.java`(신규) + `InvestigationRequest`/`InvestigationResponse` DTO, 대응 테스트
- 변경 내용 요약: in-memory `ChatMemory`(`MessageWindowChatMemory` + `InMemoryChatMemoryRepository`)를 빈으로 등록하고, `CodebotAgentService`가 단계2/3의 도구(CodeSearchTools, ObservabilityTools, KubernetesTools, LinearTools)를 바인딩한 가이드라인형 시스템 프롬프트로 조사→이슈생성을 수행한다. `POST /internal/investigations`(body: `conversationId`, `message`)로 외부(aiops 라우터)에서 호출 가능한 내부 API를 추가한다.
- 검증 방법: `.\gradlew.bat :codebot:test` (`@SpringBootTest` 또는 mock ChatClient로 컨트롤러 응답 검증)
- 롤백 방법: 해당 신규 파일 삭제
- 예상 소요: 김

### 단계 5: aiops 라우터 — Slack 앱 멘션 + 의도 분류 + 인프라 챗봇
- 변경 파일: `aiops/src/main/java/aiops/aiops/router/SlackEventController.java`(신규), `aiops/src/main/java/aiops/aiops/router/IntentClassifierService.java`(신규), `aiops/src/main/java/aiops/aiops/router/SlackBotClient.java`(신규), `aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java`(신규), `aiops/src/main/java/aiops/aiops/router/RouterService.java`(신규), `aiops/src/main/java/aiops/aiops/router/ChatMemoryConfig.java`(신규), `aiops/src/main/resources/application.yaml`(수정 — `slack.bot-token`), 대응 테스트
- 변경 내용 요약: `/slack/events`에서 URL verification challenge와 `app_mention` 이벤트를 처리하는 `SlackEventController`를 추가한다. `IntentClassifierService`는 구조화 출력(`record RouteDecision(RouteIntent intent)`, enum `INFRA/CODE/UNKNOWN`)으로 가볍게 분류한다. `RouterService`가 INFRA → `InfraChatAgentService`(기존 ObservabilityTools/KubernetesTools 재사용, ChatMemory 적용, 가이드라인형 프롬프트)를 호출하고, `SlackBotClient`(`chat.postMessage`, Bot Token)로 같은 스레드에 응답한다. CODE/UNKNOWN 분기는 단계6에서 연결한다.
- 검증 방법: `.\gradlew.bat :aiops:test` (IntentClassifierService 분류 단위 테스트, SlackEventController의 challenge/이벤트 파싱 테스트)
- 롤백 방법: 해당 신규 파일 삭제, `application.yaml` 추가분 제거
- 예상 소요: 김

### 단계 6: 라우터 ↔ codebot 연동 + E2E
- 변경 파일: `aiops/src/main/java/aiops/aiops/router/CodebotClient.java`(신규), `aiops/src/main/java/aiops/aiops/router/RouterService.java`(수정 — CODE/UNKNOWN 분기 연결), 대응 테스트
- 변경 내용 요약: `CodebotClient`가 `DiscoveryClient`로 `codebot` 인스턴스를 조회해 `POST /internal/investigations`를 호출하고, 응답 텍스트를 `RouterService`가 `SlackBotClient`로 스레드에 게시한다. UNKNOWN 분류 시 고정 안내 문구("인프라 문제인지 코드/기능 문제인지 알려주세요")를 응답한다. discovery-service + aiops + codebot을 로컬에서 동시 기동해 두 분기(INFRA/CODE) E2E를 확인한다.
- 검증 방법: 로컬 3개 서비스 기동 후 `/slack/events`에 INFRA/CODE 두 케이스의 app_mention payload를 curl로 전송, 응답 및 Slack 메시지(또는 로그) 확인
- 롤백 방법: `CodebotClient.java` 삭제, `RouterService`의 CODE/UNKNOWN 분기를 단계5 상태로 되돌림
- 예상 소요: 보통

### 단계 7: Helm 템플릿 + values
- 변경 파일: `helm/promotion-app/templates/codebot/deployment.yaml`(신규), `helm/promotion-app/templates/codebot/service.yaml`(신규), `helm/promotion-app/templates/codebot/rbac.yaml`(신규), `helm/promotion-app/values.yaml`(수정 — `codebot` 블록 추가, `aiops.slackBotToken` 추가), `helm/promotion-app/templates/aiops/deployment.yaml`(수정 — `SLACK_BOT_TOKEN` env 추가)
- 변경 내용 요약: aiops의 deployment/service/rbac 템플릿을 참고해 codebot용 Deployment(env: `GITHUB_OWNER/REPO`, `LINEAR_API_KEY`, `LINEAR_TEAM_ID`, AI API key, Eureka 등), Service, 읽기 전용 ClusterRole(ServiceAccount `codebot`, `pods`/`deployments`/`hpa` get/list/watch만)을 추가한다. aiops 쪽에는 `SLACK_BOT_TOKEN` env를 추가한다.
- 검증 방법: `helm template helm\promotion-app` 렌더링 성공, 기존 템플릿 출력 변경 없음(diff 확인)
- 롤백 방법: 신규 템플릿 파일 삭제, `values.yaml`/aiops `deployment.yaml` 추가분 제거
- 예상 소요: 보통

## 단계 실행 묶음 안내
- 단계 1: 신규 Gradle 모듈/의존성 — 단독 실행 후 승인
- 단계 2~4: codebot 내부 코드/테스트 작성 — 묶음 실행 가능 (낮은 리스크, git으로 되돌림 용이)
- 단계 5~6: aiops 라우터 코드/테스트 작성 + E2E — 묶음 실행 가능하나, 단계6의 E2E는 로컬 3서비스 동시 기동이 필요해 별도 보고
- 단계 7: Helm/인프라 변경 — 단독 실행 후 승인

## 리스크 및 대응
- 리스크 1: GitHub Code Search API가 private repo + 기존 PAT 스코프에서 기대만큼 동작하지 않을 수 있음 → 단계 2에서 실제 호출로 검증, 결과 부족 시 사용자에게 보고 후 대안 논의(별도 작업)
- 리스크 2: in-memory ChatMemory는 codebot/aiops pod 재시작 시 대화 컨텍스트 소실 → 알려진 제약으로 기록, Redis 전환은 향후 과제
- 리스크 3: 한 Slack 스레드 내에서 분류가 INFRA↔CODE로 바뀌면 각 서비스의 ChatMemory가 분리되어 컨텍스트 불일치 가능 → 빈도가 낮을 것으로 예상, 이번 범위에서는 허용(design-notes에 기록)
- 리스크 4: Linear GraphQL의 팀ID/라벨ID 해석 실패 시 이슈 생성 실패 → codebot은 실패를 조용히 무시하지 않고 Slack에 실패 사유를 보고
- 리스크 5: 신규 시크릿(`SLACK_BOT_TOKEN`, `LINEAR_API_KEY`, `LINEAR_TEAM_ID`, codebot용 `GITHUB_TOKEN`)이 다수 → 전부 placeholder 환경변수/Helm values로 처리, 실제 값은 사용자가 발급·주입

## 의존성
- 로컬 E2E를 위해 discovery-service + aiops + codebot 동시 기동 필요
- Slack 워크스페이스: Bot Token 발급(`chat:write`, `app_mentions:read` 스코프), Event Subscriptions에 `app_mention` 등록 및 Request URL(`/slack/events`) 검증 — 사용자 작업
- Linear: API Key 재발급(대화 중 노출된 키는 폐기 권고), 팀 ID 및 도메인/직무 라벨 ID 확인 — 사용자 작업
- GitHub: codebot용 PAT의 code search 권한 확인 (기존 aiops `githubClient`와 동일 owner/repo 대상)
- 기존 코드: `RestClientConfig`, `ObservabilityTools`, `KubernetesTools`, `SlackNotificationService`, `ActionApprovalService`, `AiOpsAgentService`(프롬프트 스타일 대비) 참고/재사용
