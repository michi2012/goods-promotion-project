# aiops 전체 흐름

aiops는 두 가지 핵심 기능을 가진 SRE 보조 에이전트다: **Prometheus 알람 자동 분석**과 **Slack 대화형 인프라/코드 조사 라우팅**. 이 둘을 지원하기 위해 HTTP 진입점은 실제로 4개 존재한다 — 분석·대화를 시작시키는 2개(비동기 처리) + 조치 승인을 받는 콜백 2개.

## 1. 진입점 4개

| 진입점 | 트리거 | 처리 서비스 | 비동기 처리 |
|---|---|---|---|
| `POST /webhook/prometheus` | Alertmanager 웹훅 | `AiOpsAgentService.analyze()` | `Thread.ofVirtual().start(...)` |
| `POST /slack/events` | Slack `app_mention` 이벤트(+ `url_verification` challenge 응답) | `RouterService.handleAppMention()` | `Thread.ofVirtual().start(...)` |
| `POST /slack/interactive` | Slack Interactive 버튼 클릭(승인/거절) | `SlackInteractiveController.processPayload()` | `CompletableFuture.runAsync(...)` |
| `POST /action/approve/{id}` | 수동 curl 승인(`proposeAction` 전용, 4-B 참고) | `ActionApprovalService.approve()` | 동기(즉시 처리, 짧은 작업이라 비동기 불필요) |

앞의 3개는 모두 발신 측(Alertmanager/Slack)의 짧은 응답 타임아웃(Slack은 3초) 안에 200을 먼저 반환하기 위해 비동기로 처리한다. `SlackEventController.handleEvent()`는 추가로 Slack 앱 설정 시 최초 1회 오는 `url_verification` 타입 요청에 `challenge` 값을 그대로 반환하는 처리도 겸한다.

## 2. 흐름 A — Prometheus 알람 자동 분석

```
1. PrometheusWebhookController.receiveAlert(payload)
   └─ 가상 스레드로 AiOpsAgentService.analyze(payload) 비동기 실행 → 즉시 200 응답

2. AiOpsAgentService.analyze()
   ├─ payload에서 `alerts[0].labels.alertname` 추출(실패 시 "알 수 없는 알람")
   ├─ status=="resolved" → 정상 회복 Slack 메시지만 발송, 종료
   ├─ fingerprint = payload의 `groupLabels` 노드 전체를 문자열화(`JsonNode.toString()`) → AlertDeduplicationService.isDuplicate(fingerprint) → 30분 TTL 내 중복이면 스킵(로그만 남기고 종료, Slack 발송 없음)
   └─ ChatClient.prompt()
        .system(SYSTEM_PROMPT)   ← 10단계 분석 절차 + 연쇄 장애 추론 + DLT 전용 절차 + 카나리 Istio 절차
        .user(alertPayload)
        .tools(observabilityTools, kubernetesTools, dltTools)
        .call().content()

3. SlackNotificationService.send(report) — 분석 보고서를 Slack에 발송(webhook 미설정 시 로그만 남기고 스킵)
```

`analyze()` 전체가 try-catch로 감싸여 있어, 도구 호출/파싱 중 예외가 발생해도 앱이 죽지 않고 "🔴 [AIOps 분석 실패] `{alertName}` 알람 처리 중 오류가 발생했습니다: {message}"를 Slack에 발송한 뒤 종료한다.

### SYSTEM_PROMPT 핵심 절차 (일반 알람) — `AiOpsAgentService.SYSTEM_PROMPT` 실제 순서
> 실제 프롬프트 원문의 단계 번호는 1~8 다음 **10번(Istio) → 9번(보고서 형식)** 순으로 등장한다(프롬프트 자체의 표기 오류로 보이나 LLM 실행 순서에는 영향 없음 — 마크다운 목록이라 번호가 렌더링 순서를 강제하지 않음). 아래는 실제 실행 순서 기준으로 재정렬한 것이다.

1. payload에서 alertname/severity/tier/summary/description 파악
2. `queryPrometheusMetrics`로 에러율(`rate(...{status=~"5.."}[5m])`)과 가용성 확인 — 항상 첫 도구 호출
3. `queryDatabaseHealth`로 MySQL/Redis 5개 지표(활성 커넥션·슬로우쿼리비율·HikariCP 대기·Redis 메모리·Redis 클라이언트수) 확인. HikariCP 대기>0 또는 슬로우쿼리 증가세 또는 Redis 메모리 높음 → DB 병목 후보 기록
4. 대상 서비스 식별 — payload 라벨의 `app`/`job` 우선, 없으면 2번 결과에서 에러가 가장 심한 서비스를 동적 추론
5. 4번 서비스명으로 `queryLokiLogs` 최근 5분 ERROR 로그 조회
6. 로그에 traceId 있으면 `queryTempoTrace` 호출 (없으면 스킵)
   - **6-1.** 다음 중 하나면 `queryProfilerHotspots(service, 10, 5)` 추가 호출: (a) 트레이스에서 지연은 확인됐지만 서비스 내부 어떤 코드가 병목인지 불분명(DB쿼리/외부호출/락경합 등 추측만 가능) (b) alertname/summary/description에 CPU·리소스 사용률 언급이 있어 트레이스 지연 여부와 무관하게 코드 레벨 진단이 필요
7. `queryRecentCommits(60)`로 최근 1시간 배포 이력 확인 — 장애 시각 10분 이내 커밋이면 원인 후보 1순위+롤백 권장. 배포 이력 있고 5xx 급증(`SystemErrorRateCritical`/`HighErrorBurnRateFast` 동반) 시 `proposeHelmRollback` 호출(단 인프라 포화가 단독 원인으로 의심되면 호출 안 함)
8. severity=critical + Heap/CPU/HPA 포화 알람이면 `getClusterStatus` 호출 후:
   - `KubeHPAAtMaxReplicas` → maxReplicas를 현재값+2로 `proposeHpaPatch`
   - HPA REPLICAS==MAXPODS + CPU 포화 확인 → `proposeHpaPatch`
   - `KafkaConsumerLagHigh` → `queryKafkaLag`로 실측 → 랙≥500·증가추세면 컨슈머그룹에서 서비스명 추론 후 `proposeHpaPatch`, 랙<500·감소추세면 "자연 해소 중"으로만 기록
   - `KubeHPAOverprovisioned`(minReplicas로 30분 이상 유지) → maxReplicas를 minReplicas+3으로 원복 제안
   - liveness는 정상(up==1)인데 요청이 큐잉되고 로그에 deadlock/blocked thread 확인 시 `proposeRolloutRestart`
   - "kubectl scale 권장" 문구는 절대 쓰지 않음 — HPA 서비스는 반드시 `proposeHpaPatch` 사용
10. **카나리(Istio) 시나리오** — `getClusterStatus`에서 동일 서비스 v1/v2 파드 동시 존재 시에만 진입:
    - v2 요청률 PromQL 분모가 0.05 req/s 미만이면 "샘플 부족"으로만 보고, 수치 판단 보류
    - v2 에러율만 높고 v1 정상 → `proposeTrafficShift(v1=100, v2=0)`(v2 격리) — `proposeHelmRollback`보다 우선
    - 특정 파드만 간헐적 5xx, 전체 에러율은 낮음 → `proposeOutlierDetectionUpdate` (트래픽 시프트와 동시 호출 금지)
    - `CanaryV2LatencyHigh`(v2 p99>1000ms)는 5xx와 달리 즉시 격리보다 원인 분석 우선 — v1도 같이 높으면 인프라 공통 이슈로 판단, v2만 높으면 v2 자체 회귀로 서술. 추측만으로 `proposeTrafficShift` 호출 금지
9. 위 데이터를 종합해 정해진 형식(요약/원인/영향범위/권장조치/핵심지표/구조적 개선제안)으로 보고서 작성. **구조적 개선 제안**은 이미 호출한 도구 결과에서 코드·설정 수준 패턴(N+1 의심, 반복 슬로우쿼리 등)이 보일 때만 1~2문장 — 이 항목을 위한 별도 추가 조회는 금지, 가정형 표현("~일 수 있다") 금지.

### 연쇄 장애 추론 (SYSTEM_PROMPT 내 별도 섹션 — 1~10 절차와 병행 적용)
- 원인·증상이 여러 계층(예: "타겟 DB 부하 증가 → CDC 추출 지연 → Kafka 프로듀서 블로킹 → API 스레드풀 포화 → HTTP 5xx")에 걸쳐 있는지 항상 검토
- Kafka 컨슈머 랙·CDC 지연·HikariCP 대기·Redis 지연 중 **둘 이상이 동시에 높으면** 연쇄 장애 의심
- CDC 지연 의심(타겟 DB 부하 증가 + outbox 토픽 랙 비정상 누적 또는 Kafka 유입 정지) 시 `queryDebeziumConnectorStatus` 호출 → 커넥터/태스크가 `FAILED`인 경우에만 `proposeDebeziumConnectorRestart`. `RUNNING`인데 랙만 쌓이면 커넥터 문제가 아니므로 재시작 제안 금지, DB 부하·브로커 상태를 원인으로 서술

### DLT 자동 재처리 절차 (`PurchaseDltAccumulated` 알람 전용 — 일반 절차 스킵)
1. `listUnresolvedDlt()` 호출
2. 레코드별 재처리 가능 여부 판단: `orderId != "UNKNOWN" && goodsId != null` → retryable
3. retryable 건은 `retryDlt(id)` 자동 호출
4. non-retryable 건은 "수동 처리 필요"로 Slack 보고

## 3. 흐름 B — Slack 대화형 라우팅

```
1. SlackEventController.handleEvent() — app_mention 이벤트 파싱
   └─ 가상 스레드로 RouterService.handleAppMention(channel, threadTs, text) 비동기 실행

2. RouterService.handleAppMention()
   ├─ IntentClassifierService.classify(text)
   │    Structured Output: .call().entity(RouteDecision.class) → INFRA / CODE / UNKNOWN
   ├─ UNKNOWN이면 같은 스레드(threadTs)의 직전 라우트를 캐시에서 재사용
   └─ intent에 따라 분기:
        INFRA → InfraChatAgentService.chat(threadTs, text)
        CODE  → CodebotClient.investigate(threadTs, text)  ← codebot으로 내부 HTTP 위임
        UNKNOWN → 안내 문구만 반환

3. SlackBotClient.postMessage(channel, threadTs, response) — 결과를 스레드에 게시
```

`threadRouteCache`(ConcurrentHashMap)로 한 스레드 내에서 의도가 한 번 분류된 후에는 후속 메시지가 모호해도 같은 라우트를 유지한다 — "이어서 다른 질문도" 같은 후속 발화의 재분류 실패를 방지.

### InfraChatAgentService (INFRA 라우트)
`ObservabilityTools` + `KubernetesTools`만 사용. Prometheus/Loki/Tempo 라벨 컨벤션(camelCase application 라벨 vs kebab-case K8s 리소스명)을 SYSTEM_PROMPT에 명시해 혼동을 방지한다. `propose*` 도구는 조사 근거가 있을 때만 호출하도록 강제.
- **대화 메모리**: `MessageChatMemoryAdvisor` + `ChatMemoryConfig`의 `MessageWindowChatMemory`(최대 20메시지, 인메모리 `InMemoryChatMemoryRepository`)를 사용 — Slack `threadTs`를 `ChatMemory.CONVERSATION_ID`로 사용해 같은 스레드 내 이전 대화를 기억한다(서버 재시작 시 초기화, 영속화 없음).
- 읽기 전용 K8s 조사 도구(승인 불필요): `getPodLogs(podName, tailLines)`, `getRolloutStatus(deploymentName)`, `getRolloutHistory(deploymentName)`, `getIstioMeshStatus()`(VirtualService/DestinationRule yaml 전체 조회) — Prometheus 알람 분석 흐름(`AiOpsAgentService`)에는 쓰이지 않고 InfraChatAgentService(대화형 조사)에서만 사용된다.
- 응답은 `SlackBotClient.postMessage()`로 게시되기 전 `convertMarkdownToSlackMrkdwn()`을 거쳐 `**bold**`→`*bold*`, `# 헤더`→`*헤더*`, `[텍스트](url)`→`<url|텍스트>`로 변환된다(LLM이 표준 마크다운으로 응답하지만 Slack은 자체 mrkdwn 문법을 쓰기 때문).

### CodebotClient (CODE 라우트)
Eureka로 `codebot` 인스턴스를 찾아 `POST /internal/investigations`로 내부 HTTP 위임 — aiops 자체에는 코드 검색 도구가 없다. `internalServiceClientBuilder`는 read timeout이 **60초**로 다른 내부 RestClient(3초)보다 길게 설정되어 있다 — codebot의 코드 조사(git grep + DB 조회 + LLM 추론)가 오래 걸릴 수 있기 때문.

## 4. 조치 승인 흐름 (Human-in-the-loop)

`KubernetesTools`/`ObservabilityTools`의 `propose*` 메서드는 즉시 실행하지 않고 `ActionApprovalService.propose()`로 **승인 대기열**(인메모리 `ConcurrentHashMap<id, PendingAction>`, TTL **1시간** — `propose()` 호출마다 만료분을 `removeIf`로 청소)에 등록한 뒤, Slack에 `[승인]`/`[거절]` 버튼이 포함된 **Block Kit** 메시지(`SlackNotificationService.sendBlockKit()`, 헤더+섹션+버튼 액션 블록)를 발송한다. `PendingAction`은 `record(id, actionType, params, reason, proposedAt)`.

### 4-A. Slack 버튼 승인 경로 (기본 경로)

```
1. propose*Tool 호출 → ActionApprovalService.propose(actionType, paramsJson, reason) → approvalId 발급
2. Slack에 [승인]/[거절] Block Kit 메시지 발송 (SlackNotificationService.sendBlockKit)
3. 사용자가 버튼 클릭 → POST /slack/interactive (Slack Interactive Components, form-urlencoded, payload 필드에 JSON)
   → SlackInteractiveController는 Slack 3초 타임아웃 안에 즉시 200을 반환하고 CompletableFuture.runAsync()로 비동기 처리
4. SlackInteractiveController.processPayload() — action_id를 8개 조치 타입별로 if문 분기
   ├─ reject_* → ActionApprovalService.reject(id) → 거절 안내만 response_url로 발송, 종료
   └─ approve_* → ActionApprovalService.approve(id) → execute*(pending) 호출
5. 실행 결과 + LinearAuditService.createAuditTicket(...) 감사 티켓 생성 결과를
   sendResultWithAudit()에서 하나의 문자열로 합쳐 response_url에 전송
   (SlackNotificationService.sendToResponseUrl은 replace_original=true라 두 번 나눠 보내면 첫 메시지가 덮어써지므로 반드시 합쳐서 1회 전송)
```

### 조치 타입 8종 (`action_id` ↔ `execute*` 매핑)

| actionType | 제안 도구 | execute 메서드 | 실행 커맨드 |
|---|---|---|---|
| `ROLLOUT_RESTART` | `KubernetesTools.proposeRolloutRestart` | `executeRolloutRestart` | `kubectl rollout restart deployment/{name} -n {ns}` |
| `POD_RESTART` | `KubernetesTools.proposePodRestart` | `executePodRestart` | `kubectl delete pod {podName} -n {ns}` (ReplicaSet이 자동 재생성) |
| `HPA_PATCH` | `KubernetesTools.proposeHpaPatch` | `executeHpaPatch` | `kubectl patch hpa {name} --patch '{"spec":{"maxReplicas":N}}'` |
| `HPA_MIN_PATCH` | `KubernetesTools.proposeHpaMinReplicasPatch` | `executeHpaMinReplicasPatch` | 위와 동일하되 `minReplicas` 패치 (트래픽 급증 예고 시 선제 스케일아웃용, max 조정과 별개 도구) |
| `HELM_ROLLBACK` | `KubernetesTools.proposeHelmRollback` | `executeHelmRollback` | `helm rollback {release} -n {ns}` |
| `TRAFFIC_SHIFT` | `KubernetesTools.proposeTrafficShift` | `executeTrafficShift` | `kubectl patch virtualservice {svc} --patch-file {tmp}.json`(v1/v2 weight, 임시파일 patch 후 `finally`에서 삭제) |
| `OUTLIER_DETECTION_UPDATE` | `KubernetesTools.proposeOutlierDetectionUpdate` | `executeOutlierDetectionUpdate` | `kubectl patch destinationrule {svc}`(consecutive5xxErrors 임계값) |
| `DEBEZIUM_CONNECTOR_RESTART` | `ObservabilityTools.proposeDebeziumConnectorRestart` | `executeDebeziumConnectorRestart` | Kafka Connect REST `POST /connectors/{name}/restart?includeTasks=true&onlyFailed=true` (kubectl 아님 — `kafkaConnectClient` HTTP 호출) |

**`proposeRolloutRestart`의 스레드 덤프 자동 첨부**: Slack 메시지를 보내기 전 `extractThreadDumpSummary()`가 대상 Deployment의 첫 Pod을 찾아 `kubectl exec {pod} -- kill -3 1`로 JVM 스레드 덤프를 stdout에 강제 발생시키고, 0.5초 대기 후 `kubectl logs --tail=500`으로 로그를 읽어 `BLOCKED` 상태 스레드 블록만 추출(최대 2500자)해 승인 메시지에 코드블록으로 첨부한다 — 승인자가 실제 데드락 여부를 스레드 덤프로 직접 확인하고 승인할 수 있게 하기 위함.

**`proposePodRestart`의 대상 결정**: `podName`을 직접 지정하지 않으면 `kubectl get pods -l app={deploymentName} -o jsonpath=...`로 해당 Deployment의 첫 번째 매칭 Pod을 자동 조회해 사용한다.

**커맨드 인젝션 방지**: `execute*` 메서드들은 `kubectl`/`helm` 명령을 `List<String>`으로 구성해 `ProcessBuilder`에 전달한다(문자열 concat 금지 — CLAUDE.md 절대 금지 사항).

### 4-B. 수동 curl 승인 경로 (Slack 버튼을 거치지 않는 경로)

`ObservabilityTools.proposeAction(actionType, params, reason)`은 위 8종 전용 도구에 해당하지 않는 임의의 조치(예: `KAFKA_CONSUMER_RESET`, `CACHE_FLUSH`)를 위한 범용 제안 도구다. `ActionApprovalService.propose()`로 대기열에 등록만 하고 **Slack 메시지를 보내지 않는다** — 대신 `curl -X POST http://aiops:8085/action/approve/{id}` 명령어를 보고서 텍스트에 그대로 포함해 반환한다. `ActionApprovalController.approve()`(`POST /action/approve/{id}`)가 이 curl 요청을 받아 `ActionApprovalService.approve(id)`만 호출하고 **끝난다 — 이 경로는 `execute*`를 호출하지 않으므로 실제 kubectl/helm 실행은 일어나지 않고, "승인 완료" 텍스트만 반환한다.** 즉 `proposeAction`/`ActionApprovalController` 조합은 사전 정의된 실행기가 없는 조치를 사람이 승인 로그만 남기고 직접 수행하도록 안내하는 용도이며, 8종 조치의 Slack 버튼 경로와는 실행 여부가 근본적으로 다르다.

## 5. ObservabilityTools/DltTools 전체 조회 도구 (읽기 전용, 승인 불필요)

`AiOpsAgentService`(알람 분석)와 `InfraChatAgentService`(Slack 대화)가 공유하는 `ObservabilityTools`의 조회 전용 메서드:

| 메서드 | 대상 | 비고 |
|---|---|---|
| `queryPrometheusMetrics(promql)` | Prometheus | 임의 PromQL 실행. 항상 분석의 첫 도구로 지정됨 |
| `queryDatabaseHealth()` | Prometheus | 5개 고정 쿼리(MySQL 커넥션·슬로우쿼리비율·HikariCP대기·Redis메모리·Redis클라이언트수)를 한 번에 실행해 결과를 이어붙임 |
| `queryLokiLogs(service, logqlFilter, minutes)` | Loki | `{app="service"} {filter}` LogQL 조립, 최대 50건 |
| `queryTempoTrace(traceId)` | Tempo | traceId 필수(없으면 호출 금지 — 프롬프트 규칙) |
| `queryProfilerHotspots(service, minutes, topN)` | Pyroscope | 플레임그래프 JSON에서 `self`(자체 CPU 시간, `total`은 하위호출 포함이라 집계 제외) 기준 상위 N개 메서드 추출 |
| `queryRecentCommits(minutes)` | GitHub API | `/repos/{owner}/{repo}/commits?since=...`, "시각\|작성자\|첫 줄 커밋메시지" 형식으로 가공 |
| `queryKafkaLag()` | Prometheus | `kafka_consumergroup_lag` 조회 |
| `queryDebeziumConnectorStatus()` | Kafka Connect REST | `/connectors` 전체 목록 → 커넥터별 `/connectors/{name}/status`를 순회 호출해 RUNNING/FAILED/PAUSED와 실패 태스크의 trace 첫 줄을 요약 |
| `DltTools.listUnresolvedDlt()` | serverA `GET /api/v1/admin/dlt` | UNRESOLVED DLT 전체 |
| `DltTools.retryDlt(id)` | serverA `POST /api/v1/admin/dlt/{id}/retry` | 재고 원자적 복구 + RESOLVED 처리 |

공통 규약: Loki/Tempo/Prometheus 응답은 `truncateData()`로 10,000자 초과 시 절삭(로그 경고). 모든 조회 도구는 실패 시 예외를 던지지 않고 "OO 조회 실패: {message} — 스킵하고 계속 분석하세요" 형태의 문자열을 반환해 LLM이 분석을 이어가게 한다(도구 실패가 전체 분석을 중단시키지 않도록 하는 설계).

### RestClientConfig — 타임아웃 정책

| Bean | connect timeout | read timeout | 비고 |
|---|---|---|---|
| `lokiClient`/`tempoClient`/`prometheusClient`/`pyroscopeClient`/`kafkaConnectClient`/`serverAAdminClient` | 3초 | 3초 | 관측 스택 공통 |
| `slackClient` | 기본값 | 기본값 | 타임아웃 미설정 |
| `slackBotApiClient` | 기본값 | 기본값 | `Authorization: Bearer {bot-token}` 고정 헤더, baseUrl `https://slack.com/api` |
| `linearClient` | 기본값 | 기본값 | baseUrl `https://api.linear.app/graphql`, GraphQL 단일 엔드포인트 |
| `internalServiceClientBuilder` | 3초 | **60초** | codebot 등 내부 서비스 호출용 — LLM 추론 포함이라 길게 설정 |
| `githubClient` | 5초 | 5초 | baseUrl `https://api.github.com` |

### LinearAuditService — 감사 티켓 생성

자동조치 실행 후 `createAuditTicket(actionType, paramsJson, reason, executionResult)`가 Linear GraphQL `issueCreate` mutation을 호출해 `[감사] {actionType} 자동조치 실행` 제목의 이슈를 생성한다. description에 액션 타입·파라미터·트리거 사유·실행 결과를 그대로 기록 — 모든 자동조치 실행 후(4-A 경로에서만, 4-B curl 경로는 감사 티켓을 만들지 않음) 별도 감사 로그를 남기는 것이 목적. 실패해도 예외를 던지지 않고 "Linear 감사 티켓 생성 실패: {message}" 문자열을 반환한다(Slack 메시지에 실패 사실이 그대로 노출됨).

## 6. CanaryRolloutScheduler (자동 점진 승급)

`@Scheduled(fixedRateString = "${canary.rollout.interval-ms:300000}")`로 5분마다 `server-a/b/c`의 카나리(v2) 트래픽 가중치를 점검한다.

```
for each service:
  1. KubernetesTools.getCanaryWeight(service) — 현재 v2 가중치 조회 (0/100/조회불가면 스킵)
  2. Prometheus에서 v2 요청률·에러율·p99 지연 조회
  3. 요청률 < minRequestRate(기본 0.05 req/s) → 샘플 부족, 판단 보류
  4. 에러율 > maxErrorRatePercent(기본 5%) 또는 p99 > maxP99LatencyMs(기본 1000ms) → 건강 카운트 리셋
  5. 연속 건강 확인이 healthyChecksRequired(기본 2)회 충족되면 다음 단계(10→25→50→100)로
     KubernetesTools.proposeTrafficShift() 호출 → Slack 승인 요청
  6. 동일 단계를 재제안하지 않음(쿨다운) — 승인 대기 중 중복 제안 방지
```

이 스케줄러는 **제안만 한다** — 실제 트래픽 전환은 4번 섹션의 Slack 승인 절차를 그대로 거친다.

상태는 `ConcurrentHashMap<service, CanaryState(lastWeight, consecutiveHealthyCount, lastProposedWeight)>`로 서비스별 관리된다. 판정 순서(각 단계에서 조건에 안 맞으면 즉시 상태만 갱신하고 다음 스케줄까지 대기):
1. `getCanaryWeight(service)`가 `≤0` 또는 `≥100`(카나리 진행 중 아님/조회 불가) → 상태 완전 삭제 후 스킵
2. 저장된 `lastWeight`와 현재 weight가 다르면(외부에서 수동 변경됨) 상태 리셋
3. v2 요청률 < `minRequestRate`(기본 0.05 req/s) → 샘플 부족, 판단 보류(healthy 카운트 유지도 리셋도 안 함)
4. v2 에러율 > `maxErrorRatePercent`(기본 5%) → healthy 카운트 **리셋**
5. v2 p99 지연 > `maxP99LatencyMs`(기본 1000ms) → healthy 카운트 **리셋**
6. 위 3개 통과 시 healthy 카운트 +1. `healthyChecksRequired`(기본 2)회 연속 충족 & 다음 단계(`steps`: 기본 10→25→50→100)가 존재하면 `proposeTrafficShift` 호출
7. 이미 그 단계로 제안한 적 있으면(`lastProposedWeight`와 동일) 재제안하지 않음 — 승인 대기 중 중복 방지 쿨다운

## 7. 사용 모델

| 용도 | 방식 |
|---|---|
| Slack 의도 분류 (INFRA/CODE/UNKNOWN) | Structured Output `.entity(RouteDecision.class)` |
| 알람 분석 / 인프라 대화 | Tool Calling (`.tools(...)`) |

## 8. 외부 의존성

- **Eureka(discovery-service)**: codebot 인스턴스 탐색
- **Prometheus/Loki/Tempo/Pyroscope**: 직접 URL 접속(RestClientConfig, Eureka 미사용)
- **Kubernetes API**: ServiceAccount RBAC로 kubectl 명령 실행
- **Kafka Connect(Debezium)**: 커넥터 상태 조회/재시작
- **Linear API**: 자동조치 감사 티켓 생성
- **Slack**: Webhook(알람 보고) + Bot Token(스레드 대화) + Interactive Components(승인)
