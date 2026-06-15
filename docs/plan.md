# 계획서: aiops 인프라 챗봇 — 라벨 컨벤션 반영 + 인프라 도구 화이트리스트 추가

- 작성일: 2026-06-15
- 관련 이슈/티켓: 없음

## 목표
실제 Prometheus/Loki 라벨 컨벤션을 `ObservabilityTools`의 도구 설명과 `InfraChatAgentService`의 SYSTEM_PROMPT에 반영해 LLM이 정확한 PromQL/LogQL을 생성하도록 하고, 인프라 화이트리스트 도구 4종(Pod 로그 조회, 롤아웃 상태/이력 조회, 개별 Pod 재시작, HPA minReplicas 조정)을 추가한다.

## 성공 기준
- [ ] `ObservabilityTools.queryLokiLogs`의 `@ToolParam(service)` 예시가 실제 Loki `app` 라벨 값(`serverA`/`serverB`/`serverC`)과 일치한다.
- [ ] `ObservabilityTools.queryPrometheusMetrics`의 `@ToolParam(promql)` description에 실제 라벨 컨벤션(`job="promotion-api"`, `instance="server-a:8080"` 등, `application="serverA"` 등)이 명시된다.
- [ ] `InfraChatAgentService`의 SYSTEM_PROMPT에 "라벨 컨벤션" 섹션이 추가되고, 신규 화이트리스트 도구 4종의 사용 가이드가 포함된다.
- [ ] `KubernetesTools`에 QUERY 도구 2종(Pod 로그 조회, 롤아웃 상태/이력 조회)과 ACTION 도구 2종(Pod 재시작, HPA minReplicas 조정)이 추가되고, `ActionApprovalService`/`SlackInteractiveController`에 ACTION 도구에 대응하는 `execute*`/`approve_*`/`reject_*`가 추가된다.
- [ ] `.\gradlew.bat :aiops:test --tests "aiops.aiops.tools.KubernetesToolsTest"` 통과 (신규 QUERY 도구 kubectl 실패경로 테스트 포함).
- [ ] `.\gradlew.bat :aiops:build` 전체 통과.

## 비범위 (Out of Scope)
- `getClusterStatus` 자체의 실패 응답 문구/처리 개선
- 화이트리스트 나머지 항목 (Kafka consumer offset 리셋, Alertmanager 알람 mute, DB 장시간 쿼리 KILL)
- `CodebotAgentService`의 SYSTEM_PROMPT 수정 (KubernetesTools는 공유되지만, 신규 도구 가이드는 InfraChatAgentService에만 작성)
- 기존 `proposeHpaPatch`(maxReplicas)의 시그니처/동작 변경

## 단계별 작업 계획 (최대 7단계)

### 단계 1: ObservabilityTools 라벨 컨벤션 description 수정
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java`
- 변경 내용 요약:
  - `queryLokiLogs`의 `@ToolParam(service)` 예시(67행, `"server-a, server-b, server-c, mcp"`)를 실제 Loki `app` 라벨 값(`serverA, serverB, serverC`, camelCase)으로 수정.
  - `queryPrometheusMetrics`의 `@ToolParam(promql)` description(165행)에 실제 라벨 셋 예시 추가: `job="promotion-api"`(고정값), `instance="server-a:8080"`/`"server-b:8081"`/`"server-c:8082"`, `application="serverA"`/`"serverB"`/`"serverC"`(camelCase).
  - `queryProfilerHotspots`의 `service_name="<service>"`(Pyroscope, 135행)은 Prometheus/Loki와 다른 별도 네이밍 컨벤션이므로 description에 "Pyroscope는 `serverA`/`serverB`/`serverC` 형식의 `service_name`을 사용하며 Prometheus의 `application`/Loki의 `app`과 동일한 값" 안내 추가.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: `git checkout -- aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java`
- 예상 소요: 짧음

### 단계 2: InfraChatAgentService SYSTEM_PROMPT — 라벨 컨벤션 섹션 + 신규 도구 가이드
- 변경 파일: `aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java`
- 변경 내용 요약:
  - SYSTEM_PROMPT(17-31행)에 "## 라벨 컨벤션" 섹션을 신설해 Prometheus(`job="promotion-api"`, `instance`, `application`)와 Loki(`app`)의 실제 라벨 값을 한 곳에 정리.
  - "## 조사 원칙" 섹션에 신규 QUERY 도구(Pod 로그 조회, 롤아웃 상태/이력 조회) 사용 가이드를 추가.
  - "## 조치 제안" 섹션에 신규 ACTION 도구(Pod 재시작, HPA minReplicas 조정)의 호출 조건/주의사항을 추가 (기존 `propose*` 도구들과 동일한 "근거 기반 호출" 원칙 적용).
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: `git checkout -- aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java`
- 예상 소요: 짧음

### 단계 3: KubernetesTools — QUERY 도구 2종 추가 (Pod 로그 조회, 롤아웃 상태/이력 조회)
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용 요약:
  - `getPodLogs(podName, tailLines)` — `kubectl logs <podName> -n <namespace> --tail=<tailLines>` 실행, `getClusterStatus`와 동일한 try/catch + "kubectl 접근 불가 환경일 수 있습니다" 실패 메시지 패턴. description에 "정확한 podName은 getClusterStatus 결과의 Pod 목록을 참고" 안내.
  - `getRolloutStatus(deploymentName)` — `kubectl rollout status deployment/<deploymentName> -n <namespace>`.
  - `getRolloutHistory(deploymentName)` — `kubectl rollout history deployment/<deploymentName> -n <namespace>`.
  - 3개 모두 읽기 전용 `@Tool`, `runKubectl(...)` 재사용, 승인 불필요.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: `git checkout -- aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 예상 소요: 보통

### 단계 4: Pod 재시작 ACTION 추가 (KubernetesTools + ActionApprovalService + SlackInteractiveController)
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
  - `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java`
  - `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java`
- 변경 내용 요약:
  - `KubernetesTools.proposePodRestart(podName, deploymentName, reason)` — `podName`이 비어있지 않으면 그대로 사용, 비어있으면 `kubectl get pods -n <namespace> -l app=<deploymentName> -o jsonpath={.items[0].metadata.name}`(`extractThreadDumpSummary`와 동일한 lookup 패턴)으로 첫 번째 매칭 pod을 resolve. resolve 실패 시 에러 문자열 반환(제안 등록 안 함).
  - `params = {"podName":"<resolved>","namespace":"<ns>"}`로 `approvalService.propose("POD_RESTART", params, reason)` 호출, `buildPodRestartBlocks(...)`로 Slack 발송 — **실제 resolve된 podName을 메시지에 표시**(app만 주어진 경우 "app=<deploymentName> 매칭 pod 중 첫 번째: <podName>" 안내 포함).
  - `ActionApprovalService.executePodRestart(action)` — params에서 `podName`/`namespace` 파싱 후 `kubectl delete pod <podName> -n <ns>` 실행 (`executeRolloutRestart`와 동일한 ProcessBuilder/List<String> 구조).
  - `SlackInteractiveController`에 `approve_pod_restart`/`reject_pod_restart` 라우팅 추가 (기존 `approve_restart`/`reject_restart` 블록과 동일 패턴).
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: 3개 파일 `git checkout`
- 예상 소요: 보통

### 단계 5: HPA minReplicas ACTION 추가 (KubernetesTools + ActionApprovalService + SlackInteractiveController)
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
  - `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java`
  - `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java`
- 변경 내용 요약:
  - `KubernetesTools.proposeHpaMinReplicasPatch(hpaName, newMinReplicas, reason)` — 신규 도구. `params = {"hpa":"<name>","minReplicas":N,"namespace":"<ns>"}`로 `approvalService.propose("HPA_MIN_PATCH", params, reason)` 호출, `buildHpaMinReplicasPatchBlocks(...)`로 Slack 발송 (`buildHpaPatchBlocks`와 동일 구조, 새 `action_id`).
  - `ActionApprovalService.executeHpaMinReplicasPatch(action)` — `kubectl patch hpa <hpaName> -n <ns> --patch '{"spec":{"minReplicas":N}}' --type merge` (`executeHpaPatch`와 동일 구조).
  - `SlackInteractiveController`에 `approve_hpa_min_patch`/`reject_hpa_min_patch` 라우팅 추가.
  - 기존 `proposeHpaPatch`/`executeHpaPatch`/`approve_hpa_patch`(maxReplicas)는 변경하지 않음.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: 3개 파일 `git checkout`
- 예상 소요: 보통

### 단계 6: KubernetesToolsTest — 신규 단위 테스트 추가
- 변경 파일: `aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java`
- 변경 내용 요약:
  - `getPodLogs`/`getRolloutStatus`/`getRolloutHistory`에 대해 `getCanaryWeight_kubectl실패시_minus1`과 동일한 스타일의 kubectl 실패경로 테스트(로컬 환경에서 kubectl 접근 불가 → 실패 안내 문자열 반환 확인) 추가.
  - 단계 4에서 추가되는 pod 이름 resolve 순수 로직(예: app 매칭 결과가 빈 문자열/`(결과 없음)`일 때 처리)이 별도 private/package-private 헬퍼로 분리된다면, `extractBlockedThreads`/`parseCanaryWeight`와 동일한 스타일로 단위 테스트 추가.
  - 신규 ACTION `execute*`(executePodRestart, executeHpaMinReplicasPatch)와 `SlackInteractiveController`의 `approve_pod_restart`/`approve_hpa_min_patch` 라우팅은 기존 `executeHpaPatch`/`executeRolloutRestart` 등과 동일하게 테스트를 작성하지 않음(기존 컨벤션 유지).
- 검증 방법: `.\gradlew.bat :aiops:test --tests "aiops.aiops.tools.KubernetesToolsTest"`
- 롤백 방법: `git checkout -- aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java`
- 예상 소요: 보통

### 단계 7: 전체 빌드 검증
- 변경 파일: 없음 (검증 전용)
- 변경 내용 요약: `.\gradlew.bat :aiops:build` 전체 통과 확인, `git diff --stat`로 변경 범위가 plan의 "비범위"를 침범하지 않았는지 최종 확인, `docs/checklist.md` 최종 갱신.
- 검증 방법: 위와 동일
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: 신규 kubectl 명령(`logs`, `rollout status/history`, `delete pod`, `patch hpa --patch minReplicas`)은 로컬 docker-compose에 실제 k8s API 서버가 없어 실제 동작 E2E가 불가능 → 대응: CLAUDE.md "진짜 로컬 불가(클라우드 전용)" 기준 적용, `gradle build` + 단위테스트(kubectl 실패경로) 통과까지를 커밋 전 검증 기준으로 한다. 커밋 메시지에 "EKS 배포 후 검증 필요" 명시 권고.
- 리스크 2: `KubernetesTools`는 `CodebotAgentService`(CODE 라우팅)에서도 공유되므로, 신규 `@Tool`이 의도와 무관하게 CODE 라우팅에서도 호출 가능 → 대응: `InfraChatAgentService`의 SYSTEM_PROMPT에만 사용 가이드를 추가하고 `CodebotAgentService`의 SYSTEM_PROMPT는 변경하지 않는다(비범위). 각 `@Tool`의 description 자체에 "K8s 인프라 조치/조회" 목적을 명확히 해 오용 가능성을 낮춘다.
- 리스크 3: Pod 재시작 시 `app` 라벨 다중 매칭으로 의도하지 않은 pod이 삭제될 위험 → 대응: Slack 승인 메시지에 실제 resolve된 podName을 표시해 승인자가 확인 가능하게 하고, 다중 매칭 시 "여러 개 중 첫 번째"임을 명시한다.

## 의존성
- 기존 `ActionApprovalService.propose()`/`approve()`/`reject()` 패턴
- 기존 `SlackNotificationService.sendBlockKit`/`sendToResponseUrl`
- 기존 `KubernetesTools.runKubectl(...)` 헬퍼
- `SlackInteractiveController.sendResultWithAudit(...)` (LinearAuditService 연동, 신규 ACTION에도 자동 적용됨)
