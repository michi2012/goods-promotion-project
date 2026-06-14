# 계획서: Istio 카나리(v1/v2) 에러율 기반 트래픽 격리 — 인프라 기반 마련

- 작성일: 2026-06-13
- 관련 이슈/티켓: 없음 (외부 리뷰 피드백 3번)

## 목표
server-a/b/c에 v1/v2 카나리 파드가 공존할 수 있는 구조(기본 비활성 토글)를 만들고, Prometheus가 Istio waypoint의 버전별(v1/v2) 요청 메트릭을 수집하도록 하여, aiops가 이미 보유한 카나리 트래픽 제어 로직(`AiOpsAgentService` 10번 단계, `proposeTrafficShift`/`proposeOutlierDetectionUpdate`)을 실제로 활용할 수 있게 한다.

## 성공 기준
- [ ] `helm template helm/promotion-app` (canary 기본값, 비활성) 렌더링 성공 — v1 Deployment/Service만 생성, v2 리소스 없음
- [ ] `helm template helm/promotion-app --set serverA.canary.enabled=true --set serverB.canary.enabled=true --set serverC.canary.enabled=true` 렌더링 성공 — v1/v2 Deployment의 selector가 서로 겹치지 않음 (matchLabels 직접 대조)
- [ ] `helm template helm/promotion-monitoring` 렌더링 성공 — istio-waypoint 스크랩 job이 prometheus.yml에 포함
- [ ] `.\gradlew.bat :aiops:test` 통과 (시스템 프롬프트 수정 후 컴파일 + 기존 테스트)
- [ ] `git diff`로 "비범위" 침범 없음 확인

## 비범위 (Out of Scope)
- 에러율 급증을 인위적으로 재현하는 E2E 트리거 메커니즘 (디버그 엔드포인트 등) — 별도 작업
- Micrometer 앱 레벨 `version` 공통 태그 추가 — waypoint 메트릭으로 대체
- alert-rules.yml 신규 알람 추가 — 기존 알람(`SystemErrorRateCritical` 등)으로 `analyze()` 트리거 충분
- aiops 신규 Java 도구/클래스 추가 — `queryPrometheusMetrics(promql)`로 충분
- v2(canary) Deployment의 HPA 적용 — 고정 replica
- 실제 다른 빌드의 v2 이미지 제작/배포 — 기본값은 v1과 동일 이미지, 별도 태그 입력 구조만 제공
- HPA Max + 클러스터 여유자원 컨텍스트 (2번 항목, 별도 합의로 스킵)

## 단계별 작업 계획

### 단계 1: server-a/b/c Service·Deployment에 카나리 공통 라벨 적용
- 변경 파일:
  - helm/promotion-app/templates/server-a/{deployment.yaml, service.yaml}
  - helm/promotion-app/templates/server-b/{deployment.yaml, service.yaml}
  - helm/promotion-app/templates/server-c/{deployment.yaml, service.yaml}
- 변경 내용 요약: 각 v1 Deployment의 pod template에 공통 라벨 `istio-canary-group: {{ .Values.serverX.name }}`을 추가한다 (mutable, selector 불변). 각 Service의 `spec.selector`를 `app: {{ .Values.serverX.name }}` → `istio-canary-group: {{ .Values.serverX.name }}`로 변경한다 (mutable). v1 Deployment의 `selector.matchLabels`(immutable, `app: server-X`)는 그대로 유지하면서, v2 파드를 같은 Service의 엔드포인트로 포함시킬 수 있게 된다.
- 검증 방법: `helm template helm/promotion-app`로 렌더링한 Service의 `spec.selector` 값과, v1 Deployment의 `spec.selector.matchLabels`가 기존(`app: server-X`)과 동일한지 확인
- 롤백 방법: git checkout으로 6개 파일 되돌림
- 예상 소요: 보통

### 단계 2: values.yaml에 카나리(v2) 옵션 추가
- 변경 파일: helm/promotion-app/values.yaml
- 변경 내용 요약: serverA/B/C 각각에 `canary: {enabled: false, replicas: 1, image: ""}` 섹션을 추가한다. `image`가 빈 문자열이면 v1과 동일 이미지를 사용한다.
- 검증 방법: `helm template helm/promotion-app` 기본값으로 렌더링 시 오류 없음 (canary 비활성 상태에서 미참조 값으로 인한 템플릿 오류 없는지 확인)
- 롤백 방법: git checkout values.yaml
- 예상 소요: 짧음

### 단계 3: server-a/b/c에 v2(canary) Deployment 템플릿 추가
- 변경 파일 (신규):
  - helm/promotion-app/templates/server-a/deployment-canary.yaml
  - helm/promotion-app/templates/server-b/deployment-canary.yaml
  - helm/promotion-app/templates/server-c/deployment-canary.yaml
- 변경 내용 요약: `{{- if .Values.serverX.canary.enabled }}`로 감싼 Deployment. `selector.matchLabels: {app: {{ .Values.serverX.name }}-canary}` (v1 selector `{app: server-X}`와 겹치지 않음). pod template 라벨: `app: server-X-canary`, `version: v2`, `istio-canary-group: server-X` (Service가 v2를 엔드포인트로 포함하도록). 이미지는 `.Values.serverX.canary.image`가 있으면 사용, 없으면 `.Values.serverX.image`. replicas는 `.Values.serverX.canary.replicas`, HPA 없음. 기존 deployment.yaml의 env/resources/probe 구조를 따른다 (Pyroscope initContainer는 v2에서는 생략 — 단순화).
- 검증 방법: `helm template helm/promotion-app --set serverA.canary.enabled=true --set serverB.canary.enabled=true --set serverC.canary.enabled=true`로 렌더링, v1/v2 Deployment의 `spec.selector.matchLabels`가 서로 다른지(`app: server-a` vs `app: server-a-canary`) 확인, pod label에 `version: v2`/`istio-canary-group: server-a` 포함 확인
- 롤백 방법: 3개 신규 파일 삭제
- 예상 소요: 보통

### 단계 4: Prometheus에 Istio waypoint 메트릭 스크랩 job 추가
- 변경 파일: helm/promotion-monitoring/files/prometheus.yml
- 변경 내용 요약: `kubernetes_sd_configs: role: pod`로 `promotion` 네임스페이스의 waypoint pod(라벨 `gateway.networking.k8s.io/gateway-name: waypoint` 추정)를 디스커버리하고, 포트 15020 `/stats/prometheus`에서 `istio_requests_total` 등 메트릭을 수집한다. RBAC은 기존 prometheus ClusterRole(pods/services/endpoints get/list/watch)로 충분.
- 검증 방법: `helm template helm/promotion-monitoring`으로 ConfigMap에 포함된 prometheus.yml의 YAML 유효성(파싱 성공) 확인
- 롤백 방법: git checkout prometheus.yml
- 예상 소요: 짧음

### 단계 5: aiops 시스템 프롬프트에 버전별 에러율 비교 가이드 추가
- 변경 파일: aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java
- 변경 내용 요약: SYSTEM_PROMPT 10번 단계("Istio 트래픽 제어 시나리오")에 "v1/v2 에러율 비교는 `sum(rate(istio_requests_total{destination_version="v2", response_code=~"5.."}[5m])) / sum(rate(istio_requests_total{destination_version="v2"}[5m])) * 100`(v1도 동일 패턴)을 queryPrometheusMetrics로 조회하여 비교하라"는 가이드 1~2문장을 추가한다.
- 검증 방법: `.\gradlew.bat :aiops:test` (컴파일 + 기존 테스트 통과 확인 — 문자열 변경이므로 회귀 영향 없음)
- 롤백 방법: git checkout AiOpsAgentService.java
- 예상 소요: 짧음

### 단계 6: 최종 통합 검증 및 문서화
- 변경 파일: docs/design-notes.md (조건 충족 시), docs/checklist.md
- 변경 내용 요약: `helm template` 전체(canary on/off 양쪽, promotion-app + promotion-monitoring)를 최종 렌더링하고 `git diff --stat`로 비범위 침범 여부를 확인한다. design-notes.md에는 "selector 충돌 회피를 위해 공통 라벨+Service selector 전환 방식을 선택한 이유"와 "EKS 적용 순서(라벨 롤아웃 → Service selector 전환)"를 기록한다.
- 검증 방법: `helm template helm/promotion-app --set serverA.canary.enabled=true --set serverB.canary.enabled=true --set serverC.canary.enabled=true` + `helm template helm/promotion-monitoring` 모두 성공, `git diff --stat`
- 롤백 방법: 해당 없음 (검증/문서화 단계)
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: EKS에 server-a/b/c가 이미 배포되어 있는 경우, Service selector 변경(`app:` → `istio-canary-group:`) 적용 순서가 잘못되면 일시적으로 엔드포인트가 비어 트래픽이 끊길 수 있다 → 대응: "v1 pod template에 새 라벨 추가 후 rollout 완료 → Service selector 전환" 순서를 design-notes.md에 명시한다. 이번 작업은 Helm 템플릿 작성까지만 하고, 실제 적용 순서는 사용자가 EKS에서 수행한다.
- 리스크 2: waypoint pod의 실제 라벨/포트(15020, /stats/prometheus)가 추정과 다를 수 있다 → 로컬은 `helm template` 렌더링까지만 검증하고, 실제 동작은 EKS 배포 후 Prometheus targets 페이지로 확인 필요 (커밋 메시지에 명시).
- 리스크 3: v1/v2 Deployment selector 중복으로 인한 ReplicaSet 충돌 → `helm template` 결과에서 두 selector(`app: server-X` vs `app: server-X-canary`)가 다름을 직접 대조해 검증한다.

## 의존성
- Istio Ambient(`helm/promotion-istio`)와 waypoint(`helm/promotion-istio/templates/waypoint.yaml`)가 클러스터에 이미 적용되어 있어야 waypoint 메트릭이 존재한다.
- Prometheus RBAC(`helm/promotion-monitoring/templates/metrics/prometheus.yaml`)은 변경 불필요 (이미 pods/services/endpoints get/list/watch 보유).
