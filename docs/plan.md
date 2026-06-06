# 계획서: Istio Ambient 도입 + AIOps 트래픽 제어 연동

- 작성일: 2026-06-06

## 목표
Istio Ambient mode를 도입하여 네트워크 레벨 트래픽 제어를 구현하고, AIOps가 장애 감지 시 VirtualService 가중치 조정(트래픽 시프트) 및 DestinationRule outlier detection 임계값 변경을 Slack 승인 후 실행할 수 있도록 연동한다.

## 성공 기준
- [ ] `helm upgrade promotion-istio` 후 istiod, ztunnel Pod Ready 확인 (`kubectl get pods -n istio-system`)
- [ ] `promotion` 네임스페이스 Ambient 활성화 확인 (`kubectl get ns promotion --show-labels | grep ambient`)
- [ ] VirtualService로 server-a 트래픽 v1:80% v2:20% 분기 렌더링 확인 (`helm template` 출력)
- [ ] AIOps `proposeTrafficShift` 호출 시 Slack 승인 버튼 발송 및 kubectl patch 실행
- [ ] AIOps `proposeOutlierDetectionUpdate` 호출 시 DestinationRule patch 실행
- [ ] AIOps RBAC 권한 확인 (`kubectl auth can-i patch virtualservices -n promotion --as=system:serviceaccount:promotion:aiops`)

## 비범위 (Out of Scope)
- mTLS / PeerAuthentication — 이번 작업에서 제외
- AuthorizationPolicy (서비스 간 인가 정책)
- Kiali / Jaeger 대시보드 — 기존 Grafana/Tempo 유지
- Fault injection 테스트 도구
- v2 Deployment 실제 이미지 — 구조(레이블/VirtualService subset)만 준비
- Cross-namespace traffic policy

## 단계별 작업 계획

### 단계 1: Istio Helm 차트 구성 (helm/promotion-istio/)
- 변경 파일: `helm/promotion-istio/Chart.yaml`, `helm/promotion-istio/values.yaml`, `helm/promotion-istio/templates/namespace-label.yaml`, `helm/promotion-istio/templates/waypoint.yaml`
- 변경 내용: istio/base + istiod + ztunnel Helm dependency 선언. promotion 네임스페이스 Ambient 레이블 적용 Job. L7 제어용 Waypoint Gateway 리소스 추가.
- 검증: `helm template promotion-istio ./helm/promotion-istio | grep -E "ambient|waypoint"` 렌더링 확인
- 롤백: `helm uninstall promotion-istio`
- 예상 소요: 김

### 단계 2: promotion-app — version 레이블 + VirtualService + DestinationRule
- 변경 파일: 각 서비스 `deployment.yaml` (version 레이블), `templates/*/virtualservice.yaml` (신규), `templates/*/destinationrule.yaml` (신규), `values.yaml`
- 변경 내용: Deployment `spec.template.labels`에 `version: v1` 추가. 서비스별 VirtualService(기본 v1:100%) + DestinationRule(subsets v1/v2 + outlier detection 기본값) 추가.
- 검증: `helm template promotion-app ./helm/promotion-app | grep -E "VirtualService|DestinationRule"` 렌더링 확인
- 롤백: 추가 파일 삭제, deployment.yaml version 레이블 제거
- 예상 소요: 보통

### 단계 3: KubernetesTools.java — 신규 도구 2개 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용: `proposeTrafficShift(serviceName, v1Weight, v2Weight, reason)`, `proposeOutlierDetectionUpdate(serviceName, consecutive5xxErrors, reason)` @Tool 메서드 추가
- 검증: `./gradlew :aiops:compileJava`
- 롤백: 추가 메서드 제거
- 예상 소요: 보통

### 단계 4: ActionApprovalService.java — execute 로직 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java`
- 변경 내용: `executeTrafficShift` (kubectl patch virtualservice), `executeOutlierDetectionUpdate` (kubectl patch destinationrule) 메서드 추가
- 검증: `./gradlew :aiops:compileJava`
- 롤백: 추가 메서드 제거
- 예상 소요: 보통

### 단계 5: SlackInteractiveController — 새 action_id 처리
- 변경 파일: `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java`
- 변경 내용: `approve_traffic_shift`, `reject_traffic_shift`, `approve_outlier_update`, `reject_outlier_update` action_id 분기 추가
- 검증: `./gradlew :aiops:compileJava`
- 롤백: 추가 분기 제거
- 예상 소요: 짧음

### 단계 6: AIOps RBAC + 시스템 프롬프트 업데이트
- 변경 파일: `helm/promotion-app/templates/aiops/rbac.yaml`, `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java`
- 변경 내용: `networking.istio.io` apiGroup에 `virtualservices`, `destinationrules` get/patch 권한 추가. 시스템 프롬프트에 트래픽 시프트 시나리오 판단 로직 추가.
- 검증: `kubectl auth can-i patch virtualservices -n promotion --as=system:serviceaccount:promotion:aiops`
- 롤백: rbac.yaml 이전 상태 복구
- 예상 소요: 짧음

### 단계 7: 문서 업데이트
- 변경 파일: `README.md`, `docs/arch-snapshot.md`, `docs/infra-diagram.md`
- 변경 내용: Istio Ambient + AIOps 신규 도구 반영
- 검증: 육안 확인
- 예상 소요: 짧음

## 리스크 및 대응
- **Ambient waypoint L7 의존성**: VirtualService는 waypoint proxy 없이 동작 안 함. 네임스페이스에 `istio.io/use-waypoint: waypoint` 레이블 필수 → Istio 공식 Ambient 문서 기반 설정
- **로컬 검증 한계**: Docker Desktop에서 Istio Ambient 설치 복잡 → helm template 렌더링으로 구조 검증, 실제 동작은 EKS에서 확인
- **kubectl patch VirtualService 전략**: weight 부분 패치 시 `--type merge`로 spec.http[0].route 전체를 교체하는 방식 사용
- **Istio CRD 미설치 시 template 오류**: promotion-app 렌더링이 Istio CRD에 의존 → 로컬에서는 렌더링 검증만

## 의존성
- Istio Helm repo: `helm repo add istio https://istio-release.storage.googleapis.com/charts`
- Kubernetes Gateway API CRDs (waypoint용): 별도 apply 필요
- 기존 promotion-app Deployment 구조 (server-a/b/c/gateway)
