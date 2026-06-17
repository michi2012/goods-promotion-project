# aiops 전체 흐름

aiops는 두 가지 독립된 진입점을 가진 SRE 보조 에이전트다: **Prometheus 알람 자동 분석**과 **Slack 대화형 인프라/코드 조사 라우팅**이다.

## 1. 진입점 2개

| 진입점 | 트리거 | 처리 서비스 |
|---|---|---|
| `POST /webhook/prometheus` | Alertmanager 웹훅 | `AiOpsAgentService.analyze()` |
| `POST /slack/events` | Slack `app_mention` 이벤트 | `RouterService.handleAppMention()` |

둘 다 `Thread.ofVirtual().start(...)`로 비동기 처리한다 — Alertmanager/Slack의 응답 타임아웃(Slack은 3초) 안에 200을 먼저 반환하기 위함이다.

## 2. 흐름 A — Prometheus 알람 자동 분석

```
1. PrometheusWebhookController.receiveAlert(payload)
   └─ 가상 스레드로 AiOpsAgentService.analyze(payload) 비동기 실행 → 즉시 200 응답

2. AiOpsAgentService.analyze()
   ├─ status=="resolved" → 정상 회복 Slack 메시지만 발송, 종료
   ├─ AlertDeduplicationService.isDuplicate(fingerprint) → 30분 TTL 내 중복이면 스킵
   └─ ChatClient.prompt()
        .system(SYSTEM_PROMPT)   ← 10단계 분석 절차 + DLT 전용 절차 + 카나리 Istio 절차
        .user(alertPayload)
        .tools(observabilityTools, kubernetesTools, dltTools)
        .call().content()

3. SlackNotificationService.send(report) — 분석 보고서를 Slack에 발송
```

### SYSTEM_PROMPT 핵심 절차 (일반 알람)
1. payload에서 alertname/severity/tier 파악
2. `queryPrometheusMetrics`로 에러율·가용성 확인
3. `queryDatabaseHealth`로 MySQL/Redis 병목 확인
4. 대상 서비스 식별 → `queryLokiLogs`로 ERROR 로그 조회
5. traceId 있으면 `queryTempoTrace`, 필요시 `queryProfilerHotspots`(CPU 핫스팟)
6. `queryRecentCommits(60)`로 배포 이력 확인 — 10분 이내 배포면 원인 후보 1순위, 5xx 급증 동반 시 `proposeHelmRollback`
7. severity=critical + 리소스 포화 알람이면 `getClusterStatus` → `proposeHpaPatch`/`proposeHpaMinReplicasPatch`
8. Kafka 컨슈머 랙 알람 → `queryKafkaLag`로 실제 수치 확인 후 임계치 넘으면 HPA 패치
9. 카나리 배포 중(v1/v2 동시 존재)이면 Istio 트래픽 비교 → `proposeTrafficShift`(v2 격리) 또는 `proposeOutlierDetectionUpdate`
10. 연쇄 장애 추론 — CDC 지연 의심되면 `queryDebeziumConnectorStatus` → FAILED면 `proposeDebeziumConnectorRestart`
11. 위 데이터를 종합해 정해진 형식(요약/원인/영향범위/권장조치/핵심지표/구조적 개선제안)으로 보고서 작성

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

### CodebotClient (CODE 라우트)
Eureka로 `codebot` 인스턴스를 찾아 `POST /internal/investigations`로 내부 HTTP 위임 — aiops 자체에는 코드 검색 도구가 없다.

## 4. 조치 승인 흐름 (Human-in-the-loop)

`KubernetesTools`/`ObservabilityTools`의 `propose*` 메서드는 즉시 실행하지 않고 `ActionApprovalService.propose()`로 **승인 대기열**(인메모리 `ConcurrentHashMap`, TTL 1시간)에 등록한 뒤, Slack에 `[승인]`/`[거절]` 버튼이 포함된 메시지를 발송한다.

```
1. propose*Tool 호출 → ActionApprovalService.propose(actionType, paramsJson, reason) → approvalId 발급
2. Slack에 승인/거절 버튼 메시지 발송 (SlackNotificationService)
3. 사용자가 버튼 클릭 → POST /slack/interactive (Slack Interactive Components, form-urlencoded)
4. SlackInteractiveController.processPayload()
   ├─ 거절 → ActionApprovalService.reject(id) → 거절 안내만 발송
   └─ 승인 → ActionApprovalService.approve(id) → execute*(pending) 호출
        (kubectl rollout restart / patch hpa / delete pod / helm rollback /
         kubectl patch virtualservice·destinationrule / kafka-connect restart)
5. 실행 결과 + LinearAuditService.createAuditTicket(...) 감사 티켓 생성 결과를
   하나의 Slack 메시지로 합쳐 response_url에 전송 (replace_original=true라 합쳐서 보내야 함)
```

**커맨드 인젝션 방지**: `execute*` 메서드들은 `kubectl`/`helm` 명령을 `List<String>`으로 구성해 `ProcessBuilder`에 전달한다(문자열 concat 금지 — CLAUDE.md 절대 금지 사항).

## 5. CanaryRolloutScheduler (자동 점진 승급)

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

## 6. 사용 모델

| 용도 | 방식 |
|---|---|
| Slack 의도 분류 (INFRA/CODE/UNKNOWN) | Structured Output `.entity(RouteDecision.class)` |
| 알람 분석 / 인프라 대화 | Tool Calling (`.tools(...)`) |

## 7. 외부 의존성

- **Eureka(discovery-service)**: codebot 인스턴스 탐색
- **Prometheus/Loki/Tempo/Pyroscope**: 직접 URL 접속(RestClientConfig, Eureka 미사용)
- **Kubernetes API**: ServiceAccount RBAC로 kubectl 명령 실행
- **Kafka Connect(Debezium)**: 커넥터 상태 조회/재시작
- **Linear API**: 자동조치 감사 티켓 생성
- **Slack**: Webhook(알람 보고) + Bot Token(스레드 대화) + Interactive Components(승인)
