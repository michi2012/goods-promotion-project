# 맥락 노트: Istio 카나리(v1/v2) 에러율 기반 트래픽 격리 — 인프라 기반 마련

## 왜 이 방식을 선택했는가

외부 리뷰 피드백 3번("카나리 격리 에러율 기반 롤백")을 그라운딩한 결과, **AI 측 로직은 이미 완비**되어 있음을 확인했다:
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java`의 SYSTEM_PROMPT 10번 단계("Istio 트래픽 제어 시나리오")에 "v1/v2 공존 감지 → v2 에러율 높으면 proposeTrafficShift, 간헐적이면 proposeOutlierDetectionUpdate" 로직이 이미 작성되어 있다.
- `alertmanager.yml` → `aiops:8085/webhook/prometheus` → `AiOpsAgentService.analyze()` 경로로 모든 알람이 이미 전달된다.
- `KubernetesTools`에 `proposeTrafficShift`, `proposeOutlierDetectionUpdate`, `getIstioMeshStatus`, `getClusterStatus`가 이미 존재한다.

반면 **인프라 측에는 이 로직이 작동할 조건이 없었다**:
- v1만 존재 — v2 Deployment가 없어 "카나리 공존" 상태 자체가 발생하지 않는다.
- Prometheus가 v1/v2를 구분할 수 있는 메트릭을 전혀 수집하지 않는다 (앱 메트릭은 Service 단위로 집계되어 버전 구분 불가, Istio waypoint 메트릭도 미스크랩).

따라서 이번 작업은 **AI 로직을 새로 만드는 대신, 그 로직이 작동할 수 있는 인프라 조건(v2 파드 존재 + 버전별 메트릭 가용성)을 마련**하는 것으로 범위를 좁혔다.

Istio가 **Ambient 모드**(사이드카 없음, `helm/promotion-istio`)이므로, v1/v2별 요청 통계는 네임스페이스의 `waypoint` L7 proxy(`helm/promotion-istio/templates/waypoint.yaml`)가 Envoy 표준 메트릭(`istio_requests_total{destination_version=...}`)으로 노출한다. 이는 Flagger 등 업계 표준 카나리 분석 도구가 사용하는 것과 동일한 메트릭이며, 앱 코드 변경 없이 획득 가능하다.

v2 파드가 기존 `server-X` Service / DestinationRule(v1/v2 subset)의 엔드포인트로 포함되려면 Service selector와 두 Deployment의 selector가 서로 충돌하지 않아야 한다. `server-X` Deployment의 `spec.selector.matchLabels`(`app: server-X`)는 K8s에서 **immutable**이므로 직접 변경하면 기존 배포에 `helm upgrade` 실패/재생성이 필요해진다. 대신:
- v1 pod template에 공통 라벨 `istio-canary-group: server-X`를 추가한다 (mutable — 기존 selector의 부분집합 관계는 유지됨).
- Service의 `spec.selector`를 이 공통 라벨로 전환한다 (Service selector는 항상 mutable).
- v2 Deployment는 별도 selector(`app: server-X-canary`)를 사용한다 — v1 selector(`app: server-X`)와 겹치지 않는다.

이 방식으로 v1 Deployment의 `selector.matchLabels`를 건드리지 않고 v2 파드를 같은 Service/DestinationRule 체계에 합류시킬 수 있다.

## 검토했으나 채택하지 않은 대안

### 대안 A: Micrometer common-tags로 앱 메트릭에 `version` 라벨 추가
- 무엇: `management.metrics.tags.version` 설정으로 `/actuator/prometheus` 메트릭에 버전 라벨을 부여한다.
- 왜 안 썼나: 앱 재배포가 필요하고, Istio Ambient(앱 코드 비수정)의 취지와 맞지 않는다. waypoint 메트릭으로 동일한 정보(버전별 요청/에러율)를 앱 코드 변경 없이 얻을 수 있다.

### 대안 B: v1 Deployment selector를 `{app: server-X, version: v1}`로 직접 변경 (표준 K8s 카나리 패턴)
- 무엇: v1/v2 Deployment 모두 `app+version` 조합으로 selector를 specific하게 만드는 전형적인 패턴이다.
- 왜 안 썼나: `spec.selector`는 immutable이다. 이미 클러스터에 배포된 server-X Deployment가 있다면 `helm upgrade`가 "field is immutable" 에러로 실패하고, 재생성 시 다운타임이 발생한다. 공통 라벨 추가 + Service selector 전환 방식이 무중단으로 동일한 결과를 달성한다.

### 대안 C: alert-rules.yml에 v1/v2 비교 알람(`CanaryErrorRateHigh` 등) 신규 추가
- 무엇: 버전별 에러율 비교를 위한 신규 Prometheus 알람 규칙이다.
- 왜 안 썼나: 기존 `SystemErrorRateCritical`/`Warning` 등이 이미 `analyze()`를 트리거하고, SYSTEM_PROMPT 10번 단계가 `getClusterStatus` + `queryPrometheusMetrics`로 카나리 여부와 에러율을 판단하도록 이미 작성되어 있어 별도 알람 없이도 동작 가능하다.

### 대안 D: aiops에 카나리 전용 신규 Observability 도구(`queryCanaryErrorRate` 등) 추가
- 무엇: 버전별 에러율을 반환하는 전용 `@Tool` 메서드이다.
- 왜 안 썼나: `queryPrometheusMetrics(promql)`이 자유 형식 PromQL을 그대로 실행하므로 `istio_requests_total` 쿼리도 추가 코드 없이 처리 가능하다. 시스템 프롬프트에 쿼리 가이드 1~2문장만 추가하면 충분하다 (Simplicity First).

## 기존 코드베이스 컨벤션
- Helm 서버별 디렉토리 구조: `helm/promotion-app/templates/server-{a,b,c}/{deployment,service,hpa,virtualservice,destinationrule}.yaml`, `values.yaml`의 `serverA`/`serverB`/`serverC` 섹션과 1:1 매칭. 세 서버 모두 동일 구조(필드명만 `.Values.serverX`로 치환).
- Prometheus 스크랩 설정: `kubernetes_sd_configs` 패턴은 `helm/promotion-monitoring/files/prometheus.yml`의 `cadvisor` job(role: node) 참고.
- aiops 시스템 프롬프트: `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java`의 `SYSTEM_PROMPT` 텍스트 블록, 번호 매겨진 절차 형식 (10번 단계가 카나리 시나리오).
- 테스트 구조: `aiops/src/test/java/aiops/aiops/...` — MockRestServiceServer 패턴 (`LinearAuditServiceTest`, `KubernetesToolsTest` 참고).

## 관련 파일/위치
- `helm/promotion-app/templates/server-{a,b,c}/{deployment,service}.yaml` — v1 라벨/selector 조정
- `helm/promotion-app/templates/server-{a,b,c}/deployment-canary.yaml` (신규) — v2 카나리 Deployment
- `helm/promotion-app/templates/server-{a,b,c}/{virtualservice,destinationrule}.yaml` — 기존 v1/v2 subset 정의 (변경 없음, 그대로 활용)
- `helm/promotion-app/values.yaml` — `serverA/B/C.canary` 옵션 추가
- `helm/promotion-monitoring/files/prometheus.yml` — istio-waypoint 스크랩 job 추가
- `helm/promotion-istio/templates/waypoint.yaml` — waypoint Gateway 정의 (참고, 변경 없음)
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — SYSTEM_PROMPT 10번 단계 보강
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — `proposeTrafficShift`/`proposeOutlierDetectionUpdate`/`getClusterStatus`/`getIstioMeshStatus` (변경 없음, 그대로 활용)

## 외부 참조
- 없음 (사용자 제공 외부 리뷰 원문은 직접 확인 불가 — 합의된 한 줄 요약 기준으로 그라운딩 진행)
