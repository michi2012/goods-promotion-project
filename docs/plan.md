# 계획서: K8s 관제 자동화 (AIOps + 대시보드 + 알람)

- 작성일: 2026-06-05

## 목표
AIOps가 Prometheus 알람 수신 시 K8s 클러스터 상태를 조회하고, 스케일 조치를 Slack Block Kit 버튼으로 제안한다.
엔지니어가 Slack에서 승인하면 즉시 kubectl scale이 실행된다.
SRE 대시보드와 알람 룰에도 K8s 클러스터 지표를 추가한다.

## 성공 기준
- [ ] `getClusterStatus()` 도구가 `kubectl get pods -n promotion` 결과 반환
- [ ] `proposeScale()` 호출 시 Slack에 [승인] [거절] Block Kit 버튼 메시지 전송
- [ ] Slack 버튼 클릭 → `POST /slack/interactive` → `kubectl scale` 실행 → Slack 결과 메시지
- [ ] alert-rules.yml에 Tier5-Kubernetes 그룹 (5개 알람) 추가
- [ ] sre-dashboard.json에 K8s 클러스터 row + 패널 4개 추가

## 비범위 (Out of Scope)
- kubectl rollout restart (probe 자동 처리)
- Slack App signing secret 검증 (보안 강화는 추후)
- HPA 직접 patch (kubectl scale Deployment만)
- promotion 네임스페이스 외 접근

## 단계별 작업 계획

### 단계 1: aiops Dockerfile + KubernetesTools
- 변경 파일:
  - `aiops/Dockerfile` — kubectl 바이너리 설치 레이어 추가 (v1.30.0)
  - `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` (NEW)
- 변경 내용: Dockerfile에 curl로 kubectl 다운로드 + chmod. KubernetesTools에 `getClusterStatus()`, `proposeScale()` Spring AI 도구 추가. `proposeScale()`은 ActionApprovalService 등록 + Slack Block Kit 버튼 메시지 발송.
- 검증: `.\gradlew.bat :aiops:compileJava -x test`
- 롤백: Dockerfile 이전 상태, KubernetesTools.java 삭제
- 예상 소요: 보통

### 단계 2: Slack Block Kit 발송 + Interactive 콜백 처리
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/slack/SlackNotificationService.java` — `sendBlockKit()` 메서드 추가
  - `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java` (NEW) — `POST /slack/interactive`
  - `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — `executeScale()` 연결 + kubectl ProcessBuilder 실행
- 변경 내용: Block Kit JSON 구성 후 Slack Webhook POST. Interactive 콜백은 `payload` form 파라미터 파싱 → approve/reject 분기. 승인 시 `kubectl scale deployment {name} -n promotion --replicas={n}` ProcessBuilder 실행. Slack 3초 타임아웃을 위해 즉시 200 응답 후 비동기 실행.
- 검증: `.\gradlew.bat :aiops:compileJava -x test`
- 롤백: SlackInteractiveController 삭제, SlackNotificationService sendBlockKit 제거
- 예상 소요: 보통

### 단계 3: AiOpsAgentService K8s 분석 단계 추가
- 변경 파일:
  - `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — KubernetesTools 주입, 시스템 프롬프트 K8s 분석 단계 추가
- 변경 내용: `.tools(observabilityTools, kubernetesTools)` 등록. 시스템 프롬프트 8단계에 "K8s 포드 상태 확인 → CPU/메모리 포화 시 proposeScale 제안" 단계 추가.
- 검증: `.\gradlew.bat :aiops:compileJava -x test`
- 롤백: 이전 AiOpsAgentService 복원
- 예상 소요: 짧음

### 단계 4: K8s RBAC + Gateway 라우팅
- 변경 파일:
  - `helm/promotion-app/templates/aiops/rbac.yaml` (NEW) — ServiceAccount + ClusterRole + ClusterRoleBinding
  - `helm/promotion-app/templates/aiops/deployment.yaml` — `serviceAccountName: aiops` 추가
  - `gateway-service/src/main/resources/application-k8s.yml` — `/slack/interactive` 경로 → aiops 라우트 추가
- 변경 내용: ClusterRole에 pods/deployments get/list/watch + scale subresource patch 권한. 게이트웨이에 `/slack/interactive` 라우트 추가 (Rate Limiting 미적용).
- 검증: `helm template promotion-app ./helm/promotion-app | Select-String "ClusterRole|ServiceAccount"`
- 롤백: rbac.yaml 삭제, deployment.yaml serviceAccountName 제거
- 예상 소요: 짧음

### 단계 5: K8s 알람 룰 추가
- 변경 파일:
  - `helm/promotion-monitoring/files/alert-rules.yml` — `Tier5-Kubernetes` 그룹 추가
- 변경 내용: KubePodCrashLooping, KubePodOOMKilled, KubePodNotReady, KubeNodeNotReady, KubeHPAMaxReplicas 5개 알람.
- 검증: YAML 문법 확인 (파일 직접 검토)
- 롤백: Tier5 그룹 삭제
- 예상 소요: 짧음

### 단계 6: SRE 대시보드 K8s 패널 추가
- 변경 파일:
  - `helm/promotion-monitoring/files/sre-dashboard.json` — K8s 클러스터 row + 패널 추가
- 변경 내용: "☸ [Tier 5] Kubernetes Cluster" row + Pod 재시작 횟수, Node CPU/Memory, HPA replica 현황, Deployment 가용성 패널 4개.
- 검증: JSON 유효성 확인
- 롤백: 추가한 row/패널 삭제
- 예상 소요: 보통

## 리스크 및 대응
- Slack Interactive 콜백 3초 타임아웃: kubectl 실행은 `CompletableFuture.runAsync()`로 비동기 처리, 즉시 200 응답
- kubectl 바이너리 버전 불일치: Dockerfile에 v1.30.0 고정
- RBAC 권한 누락: scale subresource는 별도 resource 명시 필요 (`deployments/scale`)

## 의존성
- Slack App에 Interactivity URL 등록 필요 (엔지니어가 직접): `https://{domain}/slack/interactive`
- aiops 파드가 K8s API 서버 접근: ServiceAccount로 자동 처리
- application-k8s.yml에 NAMESPACE env var로 네임스페이스 주입 (기본값 `promotion`)
