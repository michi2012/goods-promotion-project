# 맥락 노트: Istio Ambient 도입 + AIOps 트래픽 제어 연동

## 왜 이 방식을 선택했는가

### Ambient mode 선택
Sidecar mode 대비 파드마다 Envoy를 주입하지 않아 메모리 오버헤드가 적다. 2024년 Istio 1.22에서 GA됐으며 신규 도입 시 표준으로 자리잡는 추세. 이 프로젝트는 1vCPU 환경에서 테스트하므로 사이드카 오버헤드(파드당 ~50-100MB)를 피하는 게 맞다.

L7 트래픽 관리(VirtualService)를 위해 waypoint proxy가 필요하다. ztunnel은 L4(TCP), waypoint는 L7(HTTP) 담당으로 역할이 분리된다.

### version: v1/v2 레이블 방식 선택
별도 Deployment 이름(`server-a-v2`) 방식 대신 동일 Deployment에 `version` 레이블만 추가. 이유:
- 기존 Service, Ingress, Gateway 설정 변경 없음
- VirtualService 하나로 두 subset 가중치 조절 가능
- Helm 구조 변경 최소화

### mTLS 미포함
이 프로젝트는 인증 없음(SecurityConfig 미존재). mTLS는 서비스 간 암호화 목적이며 컴플라이언스 요구사항이 없으므로 이번 범위에서 제외. 추후 PeerAuthentication으로 별도 추가 가능.

### AIOps 트래픽 제어 레이어 분리 근거
기존 AIOps 도구(HPA 패치, Helm 롤백, 롤링 재시작)는 K8s 리소스 레벨 제어. Istio 연동은 요청 단위 네트워크 레벨 제어로 레이어가 다르다.

| 시나리오 | 기존 도구 | Istio 추가 후 |
|----------|----------|--------------|
| 배포 후 에러율 급증 | Helm 롤백 (전체 교체) | 트래픽 v2→0% 격리 후 원인 파악 → 서서히 복구 |
| 특정 파드 연속 5xx | 없음 | Outlier Detection으로 해당 파드만 자동 제거 |

## 검토했으나 채택하지 않은 대안

### 대안 A: Argo Rollouts
- 무엇: 카나리/블루그린 전용 K8s 컨트롤러
- 왜 안 썼나: AIOps와의 서킷 브레이킹 공통화, outlier detection 같은 네트워크 레벨 제어가 불가. 트래픽 관리만 원할 때 적합하나 이 프로젝트는 AIOps 연동이 핵심.

### 대안 B: Sidecar mode
- 무엇: 기존 Istio 방식, 파드마다 Envoy 사이드카 주입
- 왜 안 썼나: 파드당 50-100MB 추가 메모리. 1vCPU 테스트 환경에서 부담. Ambient mode가 동일 기능을 더 적은 리소스로 제공.

### 대안 C: Nginx Ingress canary 어노테이션
- 무엇: Ingress 레벨에서 헤더/가중치 기반 분기
- 왜 안 썼나: Ingress 진입점에서만 분기 가능, 서비스 간 내부 통신 제어 불가. Outlier detection도 없음.

## 관련 파일/위치
- `helm/promotion-istio/` — Istio 설치 전용 Helm 차트 (신규)
- `helm/promotion-app/templates/*/virtualservice.yaml` — 서비스별 VirtualService (신규)
- `helm/promotion-app/templates/*/destinationrule.yaml` — 서비스별 DestinationRule (신규)
- `helm/promotion-app/templates/aiops/rbac.yaml` — Istio CRD 권한 추가
- `aiops/.../tools/KubernetesTools.java` — proposeTrafficShift, proposeOutlierDetectionUpdate 추가
- `aiops/.../approval/ActionApprovalService.java` — execute 로직 추가
- `aiops/.../slack/SlackInteractiveController.java` — 새 action_id 처리

## 외부 참조
- Istio Ambient 공식 문서: https://istio.io/latest/docs/ambient/
- Istio Helm 설치: https://istio.io/latest/docs/setup/install/helm/
- Waypoint proxy: https://istio.io/latest/docs/ambient/usage/waypoint/
- VirtualService spec: https://istio.io/latest/docs/reference/config/networking/virtual-service/
- DestinationRule outlier detection: https://istio.io/latest/docs/reference/config/networking/destination-rule/#OutlierDetection
