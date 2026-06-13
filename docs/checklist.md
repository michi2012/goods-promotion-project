# 체크리스트: Router-Worker 챗봇 — aiops 인프라 라우터 + codebot 코드조사/Linear 티켓 생성 (묶음 A)

- 마지막 업데이트: 2026-06-13

## 진행 상황
- [x] 단계 1: codebot 모듈 스캐폴딩 (`settings.gradle`, `codebot/build.gradle`, `CodebotApplication`, `application.yaml`)
  - [x] 검증 통과 (`.\gradlew.bat :codebot:build`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: codebot 도구 — CodeSearchTools, ObservabilityTools(자체), KubernetesTools(읽기전용) + RestClientConfig
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: codebot 도구 — LinearTools(createIssue) + linearClient
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: codebot 에이전트(ChatMemory + 가이드라인형 프롬프트) + `/internal/investigations` API
  - [x] 검증 통과 (`.\gradlew.bat :codebot:test`)
  - [ ] 코드리뷰 통과
- [x] 단계 5: aiops 라우터 — SlackEventController, IntentClassifierService, SlackBotClient, InfraChatAgentService, RouterService(INFRA 분기)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — BUILD SUCCESSFUL, 18/18)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 라우터 ↔ codebot 연동(CodebotClient) + RouterService CODE/UNKNOWN 분기 + 로컬 E2E
  - [x] 검증 통과 (로컬 3서비스 기동 후 INFRA/CODE app_mention curl 테스트 — 아래 "최종 검증" 참고)
  - [ ] 코드리뷰 통과
- [x] 단계 7: Helm 템플릿(`codebot/`) + values + aiops `SLACK_BOT_TOKEN`
  - [x] 검증 통과 (`helm template helm\promotion-app` — exit 0, 기존 템플릿 출력 비파괴)
  - [ ] 코드리뷰 통과

## 최종 검증
- [ ] `.\gradlew.bat build` 전체 모듈(codebot 포함) 빌드/테스트 통과 — `serverA:test`(Testcontainers)에서 BUILD FAILED, 이번 plan 범위 무관 (아래 발견 사항 참고). codebot/aiops 등 이번 plan 대상 모듈은 개별 `:test` 통과 확인됨(단계 5/6 참고)
- [x] `helm template helm\promotion-app` 렌더링 성공 (기존 템플릿 출력 비파괴, exit 0)
- [x] 로컬 E2E: INFRA 분기 — app_mention → 의도분류 INFRA → RouterService → InfraChatAgentService 정상 호출 확인 (로그: `[Router] 의도 분류 결과: INFRA`). ChatMemory 멀티턴(후속 질문) 시나리오는 미검증 — 단발성 호출 경로만 확인.
- [x] 로컬 E2E: CODE 분기 — app_mention → 의도분류 CODE → CodebotClient(Eureka discovery) → codebot `/internal/investigations` → `CodebotAgentService.investigate()` 정상 호출 확인 (로그: `[Router] 의도 분류 결과: CODE`, codebot 측 `CodeSearchTools` 호출 확인). Linear 이슈 생성까지는 미검증 — `GITHUB_TOKEN` 부재로 코드 검색이 401 실패하여 에이전트가 RCA 근거 부족으로 `createIssue`를 호출하지 않음(프롬프트 지침상 정상 동작).
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 — 공유 ObservabilityTools 모듈 추출 없음, Redis ChatMemory 없음, PR/코드수정 루프 없음, Slack App 설정/Linear 팀ID 하드코딩 없음, codebot replicas=1 유지. 모두 위반 없음.
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 — `git diff --stat` 9개 파일(aiops 라우터 연동 3개, helm aiops deployment 1개, helm values 1개, docs 3개, settings.gradle 1개) + 신규 `aiops/.../router/`, `codebot/`, `helm/promotion-app/templates/codebot/`. 모두 이번 plan 범위 내 변경.
- [x] 민감 정보(`SLACK_BOT_TOKEN`, `LINEAR_API_KEY`, `LINEAR_TEAM_ID`, GitHub PAT 등)가 코드/로그/응답에 노출되지 않았는지 확인 — values.yaml의 모든 신규 시크릿 필드는 빈 문자열 placeholder (`helm upgrade --set`으로 주입), 코드/로그/체크리스트에 실제 키 값 노출 없음 (grep 확인)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 대화 중 노출된 Linear API Key(`lin_api_84AAA...`)는 폐기 후 재발급 권장 — 코드에는 환경변수 placeholder만 사용
- (해결) Linear 팀 ID = `aab03cf6-0c6c-4a56-a551-225fca2542cf` (워크스페이스 "Michi2012") — Linear MCP로 확인. 도메인(주문/결제/프로모션/유저)·직무(인프라/프론트엔드/백엔드) 라벨 모두 존재 확인됨 — `LinearTools.resolveLabelId()`가 이름으로 조회하므로 ID 하드코딩 불필요.
- (해결, 코드 변경) `aiops/src/main/resources/application.yaml`의 `eureka.client.fetch-registry`가 `false`였음 — 이 값이면 `DiscoveryClient.getInstances("codebot")`이 항상 빈 리스트를 반환해 `CodebotClient`가 codebot을 절대 찾지 못함. `gateway-service`의 기존 컨벤션(`fetch-registry: true`)을 따라 `true`로 수정. 로컬 E2E에서 CODE 분기가 codebot에 정상 위임되는 것으로 수정 효과 확인됨.
- (신규, 로컬 한정) aiops `logback-spring.xml`은 root logger appender가 `springProfile name="local"`/`"k8s"` 블록 안에만 정의되어 있음 — `SPRING_PROFILES_ACTIVE` 미설정 시 root logger에 appender가 0개가 되어 로그가 전부 버려짐. docker-compose/k8s 환경은 항상 `SPRING_PROFILES_ACTIVE`를 설정하므로 실제 배포에는 영향 없음. 코드 수정 불필요, 로컬에서 `gradlew bootRun`/`java -jar`로 직접 띄울 때만 `SPRING_PROFILES_ACTIVE=local` 필요.
- (신규) Slack Bot Token에 `chat:write` scope가 없어 `chat.postMessage`가 `missing_scope`로 실패함 (INFRA/CODE 양쪽 E2E에서 동일하게 확인). Slack 앱 OAuth 권한에 `chat:write` 추가 후 재설치(토큰 재발급) 필요 — 이번 plan 범위 밖, 별도 처리.
- (해결, 코드 변경) codebot에 `GITHUB_TOKEN`(GitHub PAT)이 설정되어 있지 않아 `CodeSearchTools`의 코드 검색이 `401 Unauthorized`로 실패함. 단계 7에서 `codebot.githubToken` Helm value + `GITHUB_TOKEN` env로 반영함. 실제 PAT 값은 `helm upgrade --set codebot.githubToken=<PAT>`로 사용자가 주입 필요.
- (참고) codebot의 Google GenAI API 키 env var 이름은 `AI_API_KEY`(aiops는 `SPRING_AI_GOOGLE_GENAI_API_KEY`)로, 두 서비스의 네이밍이 다름 — 기존 코드(`codebot/application.yaml`, 단계1)부터의 컨벤션이라 이번 plan에서는 그대로 따름(Helm value `codebot.aiApiKey` → env `AI_API_KEY`).
- (참고) 로컬 E2E에서는 docker-compose 풀스택(kubectl 컨텍스트, Loki, Prometheus)이 떠 있지 않아 `ObservabilityTools`/`KubernetesTools` 호출이 모두 실패함 — 코드 결함 아님, 도구의 실패 처리(빈 결과 반환 후 LLM이 계속 진행)가 정상 동작하는 것까지는 확인됨.
- (신규, 이번 plan 범위 무관) `.\gradlew.bat build` 전체 모듈 빌드 시 `serverA:test`(Testcontainers 기반: ServerAApplicationTests, GoodsRepositoryConcurrencyTest, GoodsRepositoryTest, RedisStockServiceTest)가 `DockerClientProviderStrategy` 오류로 6/37 실패 → `BUILD FAILED`. `docker info`로 Docker 데몬은 정상 동작 확인됨. `serverA`는 이번 세션에서 전혀 수정하지 않은 모듈(git diff에 없음) — 로컬 환경(Windows, Docker Desktop의 Testcontainers 연동 설정 등)의 기존 이슈로 추정. 별도 확인/조치 필요.
