# 맥락 노트: Karpenter 차트 추가 및 aiops 노드 상태 조회

## 왜 이 방식을 선택했는가

**왜 Karpenter인가 (Cluster Autoscaler 대신)**:
기존 Cluster Autoscaler는 ASG(Auto Scaling Group) 기반으로 노드를 프로비저닝하며,
Karpenter는 Pending Pod를 직접 보고 최적 인스턴스 타입을 즉시 프로비저닝함.
반응 속도(수초 vs 수분)와 비용 최적화(Spot 자동 fallback) 모두 Karpenter가 우위.

**왜 aiops가 노드를 직접 제어하지 않는가**:
Karpenter가 자동으로 처리하는 영역에 aiops가 개입하면 Dual Controller 충돌 발생.
aiops의 역할은 "노드 상황을 보고서에 포함하는 것"으로 한정.
Slack 승인을 요하는 수동 노드 조작은 Karpenter 자동화가 이미 처리하므로 불필요.

**차트 구조 선택 (promotion-karpenter 독립 차트)**:
promotion-istio가 독립 차트로 인프라 레이어를 분리한 패턴과 동일.
Karpenter는 클러스터 레벨 인프라이므로 앱 차트(promotion-app)와 분리가 맞음.
Karpenter controller를 OCI dependency로 포함해 `helm install`만으로 완전 설치 가능하게 함.

**Spot + On-demand 혼합 선택**:
사용자 명시적 선택. Karpenter가 Spot 인터럽션 시 자동으로 On-demand로 교체하므로
서비스 중단 없이 비용 60~70% 절감 가능.

## 검토했으나 채택하지 않은 대안

### Cluster Autoscaler
- 무엇: ASG 기반 기존 K8s 표준 autoscaler
- 왜 안 썼나: 반응 속도 느림(수분), 인스턴스 타입 선택 제한, AWS EKS에서 Karpenter가 공식 권장

### aiops에 노드 프로비저닝 도구 추가
- 무엇: AWS EC2 API 또는 ASG API를 호출해 노드를 직접 추가/제거
- 왜 안 썼나: Karpenter와 충돌, 승인 지연(수 분) 동안 Pod가 Pending 상태 유지,
  Karpenter가 이미 수초 내 자동 처리

### promotion-infra에 포함
- 무엇: 기존 infra 차트에 Karpenter 리소스 추가
- 왜 안 썼나: Karpenter는 클러스터 전역 컴포넌트, promotion-infra는 앱 네임스페이스 인프라 담당.
  관심사 분리가 더 명확함.

## 기존 코드베이스 컨벤션
- 차트 구조: `helm/promotion-*/` — 독립 차트, dependency로 외부 컴포넌트 포함
- 민감값: 모두 `--set`으로 주입, values.yaml은 placeholder(`""`)
- nodeSelector: `role: app` — NodePool spec.template.metadata.labels에 동일 라벨 부착 필요

## 관련 파일/위치
- `helm/promotion-karpenter/` — 신규 차트 (Karpenter controller + NodePool + EC2NodeClass)
- `helm/promotion-app/values.yaml` — `nodeSelector.role: app` 확인
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — getClusterStatus() 수정

## 외부 참조
- Karpenter 설치 가이드: https://karpenter.sh/docs/getting-started/getting-started-with-karpenter/
- NodePool API v1: https://karpenter.sh/docs/concepts/nodepools/
- EC2NodeClass API v1: https://karpenter.sh/docs/concepts/nodeclasses/
