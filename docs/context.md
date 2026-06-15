# 맥락 노트: aiops 인프라 챗봇 — 라벨 컨벤션 반영 + 인프라 도구 화이트리스트 추가

## 왜 이 방식을 선택했는가

### 배경
Slack에서 `InfraChatAgentService`(INFRA 라우팅)를 실사용 테스트한 결과, Prometheus/Loki 호출 자체(`callPrometheus`, `queryLokiLogs`)는 HTTP 200으로 "성공"하지만 LLM이 생성한 PromQL/LogQL이 실제 라벨 값과 달라 빈 결과를 받고 "조회되지 않는다"는 모호한 답변을 반환했다. 실제 확인된 라벨 컨벤션:
- Prometheus: `job="promotion-api"`(고정값), `instance="server-a:8080"`/`"server-b:8081"`/`"server-c:8082"`, `application="serverA"`/`"serverB"`/`"serverC"` (예: `process_cpu_usage{application="serverA", instance="server-a:8080", job="promotion-api"}`)
- Loki: `app` 라벨 값 = `"serverA"`/`"serverB"`/`"serverC"` (camelCase) — `ObservabilityTools.queryLokiLogs`의 `@ToolParam` 예시 `"server-a, server-b, server-c, mcp"`(67행)와 불일치, 수정 필요.
- Pyroscope: `service_name="<service>"` — Prometheus/Loki와 별도 네이밍 컨벤션이지만 값 자체(`serverA` 등)는 동일.

### 결정 1 — 라벨 컨벤션 정보 배치: "둘 다"
`ObservabilityTools`의 `@Tool`/`@ToolParam` description과 `InfraChatAgentService`의 SYSTEM_PROMPT 양쪽에 라벨 컨벤션을 명시하기로 했다.
- description만 수정하면 LLM이 도구 호출 시점에만 정보를 보고, 여러 도구를 조합한 추론(예: Prometheus 결과와 Loki 결과를 연관 짓는 경우) 단계에서는 컨벤션 정보가 컨텍스트에 없을 수 있다.
- SYSTEM_PROMPT만 수정하면 각 `@ToolParam`의 예시 문자열(`"server-a, server-b, server-c, mcp"` 등)이 실제 값과 계속 불일치한 상태로 남아 혼란을 유발한다.
- 이전 plan들에서 "프롬프트 레벨 보강이 코드(로직) 변경보다 안정적"이라는 결론이 반복적으로 확인되었으므로, 이번에도 코드 로직 변경 없이 description/SYSTEM_PROMPT 텍스트만 수정한다.

### 결정 2 — HPA minReplicas: 별도 신규 도구
기존 `proposeHpaPatch`/`executeHpaPatch`/`buildHpaPatchBlocks`/`approve_hpa_patch`는 `maxReplicas` 전용으로 설계되어 있다(`KubernetesTools.java:88-103`, `ActionApprovalService.java:95-128`). 이를 확장해 `minReplicas`까지 같은 도구에서 다루면:
- `params` JSON 스키마가 `{"hpa":..., "maxReplicas":..., "minReplicas":...}` 형태로 분기되어 `executeHpaPatch`의 patch JSON 생성 로직과 Slack 메시지 문구가 모두 조건분기를 가져야 한다.
- 기존 `approve_hpa_patch`/`reject_hpa_patch` 핸들러와 `buildHpaPatchBlocks`의 단순함이 깨진다.
별도의 `proposeHpaMinReplicasPatch`/`executeHpaMinReplicasPatch`/`buildHpaMinReplicasPatchBlocks` + 새 action type `HPA_MIN_PATCH` + `approve_hpa_min_patch`/`reject_hpa_min_patch`를 신설하면 기존 maxReplicas 흐름은 완전히 그대로 유지되고, 새 흐름은 `proposeHpaPatch` 트리오를 그대로 복제한 단순한 구조가 된다. "기존 동작 변경 없음"을 보장하는 쪽을 택했다.

### 결정 3 — Pod 재시작 대상 지정: podName 선택 + app fallback
`proposePodRestart`는 `podName`(선택)과 `deploymentName`을 받는다.
- `podName`이 주어지면 그대로 사용.
- `podName`이 비어있으면 `KubernetesTools.extractThreadDumpSummary`(267-285행)와 동일한 패턴으로 `kubectl get pods -n <namespace> -l app=<deploymentName> -o jsonpath={.items[0].metadata.name}`을 실행해 첫 번째 매칭 pod을 resolve.
- 다중 매칭 시 "첫 번째"를 선택하고, Slack 승인 메시지에 실제 resolve된 podName과 "app=<deploymentName> 매칭 pod 중 첫 번째"임을 명시해 승인자가 의도한 대상인지 확인할 수 있게 한다.
이 방식을 택한 이유: (a) 사용자가 Slack에서 "server-a pod 재시작해줘"처럼 정확한 pod 이름을 모르고 요청하는 경우가 흔하므로 app 기반 fallback이 실용적이고, (b) 동시에 LLM이 `getClusterStatus` 등으로 정확한 pod 이름을 이미 알고 있다면 그 값을 그대로 쓸 수 있어 두 시나리오를 모두 지원한다. (c) "전체 매칭 pod 삭제"는 의도치 않은 광범위한 영향을 줄 수 있어 제외했고, "매칭되면 에러로 거부"는 사용성이 떨어져 제외했다 — 대신 Slack 승인 단계에서 사람이 최종 확인하므로 안전장치가 이미 있다.

### 결정 4 — 테스트 범위: QUERY 실패경로 + 순수로직만
`KubernetesToolsTest.java`는 현재 ACTION의 `execute*`/`propose*`나 `SlackInteractiveController`의 `approve_*`/`reject_*` 라우팅에 대한 테스트가 전혀 없다(순수 로직 `extractBlockedThreads`/`parseCanaryWeight`와 kubectl 실패경로 `getCanaryWeight_kubectl실패시_minus1`만 존재). 이번 작업에서도 이 컨벤션을 따른다:
- 신규 QUERY 도구(`getPodLogs`, `getRolloutStatus`, `getRolloutHistory`)는 `getCanaryWeight_kubectl실패시_minus1`과 동일한 스타일로 로컬 kubectl 접근 불가 시 실패 안내 문자열을 반환하는지만 검증.
- 신규 ACTION(`executePodRestart`, `executeHpaMinReplicasPatch`)과 `approve_pod_restart`/`approve_hpa_min_patch` 라우팅은 기존 컨벤션과 동일하게 테스트 작성하지 않음.
"신규 코드는 전부 테스트해야 한다"는 더 엄격한 대안도 검토했으나, 기존 ACTION 트리오들에 대한 테스트 인프라(approval mock, Slack response_url mock 등)가 전혀 갖춰져 있지 않아 이번 작업에서 그 인프라를 새로 구축하는 것은 범위를 크게 벗어난다고 판단했다.

### KubernetesTools 공유 컴포넌트 이슈
`KubernetesTools`는 `InfraChatAgentService`(INFRA)와 `CodebotAgentService`(CODE) 양쪽에 주입되어 사용된다. 신규 `@Tool`은 `KubernetesTools`에 추가하되(다른 라우팅에서도 호출 가능한 공용 컴포넌트이므로), 사용 가이드(언제/어떻게 호출해야 하는가)는 `InfraChatAgentService`의 SYSTEM_PROMPT에만 추가한다. `CodebotAgentService`의 SYSTEM_PROMPT는 변경하지 않는다(비범위). 오용 가능성은 각 `@Tool`/`@ToolParam`의 description 자체에 "K8s 인프라 조치/조회 목적"을 명확히 기술해 낮춘다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 기존 proposeHpaPatch를 확장해 minReplicas 지원
- 무엇: `proposeHpaPatch(hpaName, newMinReplicas, newMaxReplicas, reason)`처럼 시그니처를 확장하고 `executeHpaPatch`/`buildHpaPatchBlocks`에 조건 분기 추가.
- 왜 안 썼나: 기존 `approve_hpa_patch` 흐름과 patch JSON 생성 로직에 분기가 생겨 "기존 동작 변경 없음"을 보장하기 어렵고, plan의 비범위 항목("기존 proposeHpaPatch 동작 변경 없음")과 충돌한다.

### 대안 B: Pod 재시작 — app 라벨만 받고 항상 첫 매칭 pod 사용 (podName 파라미터 없음)
- 무엇: `proposePodRestart(deploymentName, reason)`만 받고 항상 `app=<deploymentName>` 매칭 pod 중 첫 번째를 대상으로 함.
- 왜 안 썼나: LLM이 이미 특정 pod 이름을 알고 있는 상황(예: 스레드 덤프 확인 후 특정 pod을 재시작하고 싶은 경우)에 의도한 pod을 지정할 방법이 없다. `podName` 선택 파라미터를 추가하는 것이 더 유연하고 구현 비용이 거의 동일하다.

### 대안 C: 신규 코드 전체에 대해 완전한 테스트 커버리지 작성 (ACTION execute*, Slack 라우팅 포함)
- 무엇: `executePodRestart`/`executeHpaMinReplicasPatch`와 `approve_pod_restart`/`approve_hpa_min_patch` 라우팅까지 모두 단위 테스트 작성.
- 왜 안 썼나: `ActionApprovalService`/`SlackInteractiveController`에 대한 테스트 파일이 현재 전혀 존재하지 않아(`aiops/src/test/java/aiops/aiops/**/*.java` 9개 파일 중 없음), 이번 작업에서 그 테스트 인프라(approval mock, response_url mock, ProcessBuilder mock 등)를 새로 구축하는 것은 범위를 벗어난다. 기존 ACTION 트리오(`executeRolloutRestart`, `executeHpaPatch` 등)도 동일하게 테스트가 없어 컨벤션 일관성도 근거가 된다.

## 기존 코드베이스 컨벤션

### ACTION 도구 트리오 패턴 (propose / execute / approve+reject / buildXBlocks)
- `propose*`(`KubernetesTools.java`): `approvalService.propose(actionType, paramsJson, reason)`으로 `PendingAction` 등록 → ID 발급 → `slackService.sendBlockKit(title, buildXBlocks(id, ...))`로 [승인]/[거절] 버튼 포함 메시지 전송.
  - 예: `proposeRolloutRestart`(56-77행), `proposeHpaPatch`(88-103행).
- `execute*`(`ActionApprovalService.java`): params JSON 파싱 → `List<String> command`로 kubectl 명령 구성 → `ProcessBuilder`(`redirectErrorStream(true)`) → stdout 읽기 → exit code 확인 → `"✅ ... 완료: ..."` 또는 `"❌ ... 실패 (exitCode=...): ..."` 또는 `"❌ ... 실행 중 오류: " + e.getMessage()` 반환.
  - 예: `executeRolloutRestart`(62-93행), `executeHpaPatch`(95-128행, patch JSON `"{\"spec\":{\"maxReplicas\":%d}}"`).
- `approve_X`/`reject_X`(`SlackInteractiveController.java`): `processPayload`(39-156행) 내 순차 `if` 블록. `reject_X`는 `approvalService.reject(approvalId)` 후 거절 메시지 전송. `approve_X`는 `approvalService.approve(approvalId)` → 빈 값이면 "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다" → 아니면 `sendResultWithAudit(responseUrl, pending, approvalService.executeX(pending))`(160-164행, Linear 감사 티켓 자동 생성).
- `buildXBlocks`(`KubernetesTools.java`): header + section(대상/이유 등 표시) + actions(approve_X/reject_X 버튼, `value=id`).
  - 예: `buildHpaPatchBlocks`(323-357행).

### 읽기 전용 QUERY 도구 패턴
- `getClusterStatus()`(36-54행), `getIstioMeshStatus()`(128-144행): try/catch로 `runKubectl(...)` 호출, 성공 시 포맷된 텍스트 반환, 실패 시 `"K8s 클러스터 조회 실패 — kubectl 접근 불가 환경일 수 있습니다: " + e.getMessage()`류의 안내(LLM에게 "스킵하고 계속"하도록 유도). 승인 불필요.

### kubectl 실행 헬퍼
- `runKubectl(String... args)`(467-486행, private): `List<String> command = new ArrayList<>(); command.add("kubectl"); command.addAll(List.of(args));` → `ProcessBuilder`(redirectErrorStream(true)) → stdout 읽기 → `exitCode != 0`이면 `RuntimeException("kubectl 종료 코드 " + exitCode + ": " + output)` → 빈 출력이면 `"(결과 없음)"` 반환.
- CLAUDE.md 규칙: kubectl/helm 명령은 문자열 concat 금지, 반드시 `List<String>` 구성 — 모든 신규 kubectl 호출은 `runKubectl(...)`(QUERY) 또는 동일한 `List<String>` 패턴(ACTION, `ActionApprovalService` 내부)을 따른다.

### Pod lookup 패턴 (app 라벨 fallback)
- `extractThreadDumpSummary(deploymentName)`(267-285행): `runKubectl("get","pods","-n",namespace,"-l","app="+deploymentName,"-o","jsonpath={.items[0].metadata.name}").trim()` → 결과가 blank 또는 `"(결과 없음)"`이면 실패 처리. `proposePodRestart`의 podName fallback이 이 패턴을 그대로 재사용한다.

### 테스트 구조
- `KubernetesToolsTest.java`(122행): `@ExtendWith(MockitoExtension.class)`, `@Mock ActionApprovalService`/`@Mock SlackNotificationService`, `new KubernetesTools(approvalService, slackService, "promotion")`. 순수 로직(`extractBlockedThreads`, `parseCanaryWeight`) 단위 테스트 + kubectl 실패경로 테스트(`getCanaryWeight_kubectl실패시_minus1`, `-1` 반환 검증) 1개.

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java` — `queryLokiLogs`(66-96행, `@ToolParam(service)` 67행 수정 대상), `queryPrometheusMetrics`(164-174행, `@ToolParam(promql)` 165행 수정 대상), `queryProfilerHotspots`(128-156행, Pyroscope `service_name` 컨벤션 안내 추가 대상).
- `aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java` — `SYSTEM_PROMPT`(17-31행), "## 조사 원칙"/"## 조치 제안" 섹션에 라벨 컨벤션 + 신규 도구 가이드 추가.
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — 신규 QUERY 3종(`getPodLogs`, `getRolloutStatus`, `getRolloutHistory`), 신규 ACTION 2종(`proposePodRestart`, `proposeHpaMinReplicasPatch`) + `buildPodRestartBlocks`/`buildHpaMinReplicasPatchBlocks` 추가 위치. `runKubectl`(467-486행) 재사용.
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — `executePodRestart`, `executeHpaMinReplicasPatch` 추가 위치. `executeHpaPatch`(95-128행)가 `executeHpaMinReplicasPatch`의 템플릿.
- `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java` — `processPayload`(39-156행)에 `approve_pod_restart`/`reject_pod_restart`, `approve_hpa_min_patch`/`reject_hpa_min_patch` 라우팅 추가.
- `aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java` — 신규 QUERY 실패경로 테스트 추가 위치.

## 외부 참조
없음
