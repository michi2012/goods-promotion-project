# 계획서: Karpenter 차트 추가 및 aiops 노드 상태 조회

- 작성일: 2026-06-07

## 목표
EKS 노드 자동 프로비저닝을 위한 `promotion-karpenter` Helm 차트를 추가하고,
aiops `getClusterStatus()`에 노드 상태 조회를 추가해 보고서에 노드 현황이 포함되도록 한다.

## 성공 기준
- [ ] `helm/promotion-karpenter/` 차트 생성 — Chart.yaml, values.yaml, templates 2개(NodePool, EC2NodeClass)
- [ ] `helm template promotion-karpenter ./helm/promotion-karpenter --set clusterName=test` 렌더링 오류 없음
- [ ] `getClusterStatus()`가 Pods/Deployments/HPA 외 Nodes 항목 반환
- [ ] `.\gradlew.bat :aiops:compileJava` 오류 없음

## 비범위 (Out of Scope)
- Karpenter controller IAM Role / SQS 인터럽션 큐 AWS 리소스 직접 생성 (Terraform/CDK 영역)
- Karpenter controller 설치 검증 (EKS 클러스터 필요)
- aiops에 노드 프로비저닝 직접 제어 도구 추가 (Karpenter가 자동 처리)

## 단계별 작업 계획

### 단계 1: helm/promotion-karpenter/ 차트 생성
- 변경 파일: (신규) `helm/promotion-karpenter/Chart.yaml`, `values.yaml`,
  `templates/nodepool.yaml`, `templates/ec2nodeclass.yaml`
- 변경 내용:
  - Chart.yaml: karpenter 1.0.x를 OCI dependency로 선언 (`oci://public.ecr.aws/karpenter`)
  - values.yaml: clusterName·clusterEndpoint·nodeRole·karpenterRoleArn 등 민감값은 `--set` 주입 placeholder
  - NodePool: Spot + On-demand 혼합, `role: app` 라벨 (promotion-app nodeSelector와 매핑),
    인스턴스 카테고리 c/m/r, 세대 4~6, CPU 10 / 메모리 40Gi 상한
  - EC2NodeClass: AL2023, subnet/SG는 `karpenter.sh/discovery` 태그 기반 자동 선택
- 검증 방법: `helm template promotion-karpenter ./helm/promotion-karpenter --set clusterName=test`
- 롤백 방법: `rm -rf helm/promotion-karpenter/`
- 예상 소요: 보통

### 단계 2: KubernetesTools.java — getClusterStatus() 노드 상태 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용:
  - `kubectl get nodes --no-headers` 결과를 `[Nodes]` 섹션으로 기존 출력에 추가
  - 노드 이름·상태(Ready/NotReady)·역할·AGE·버전 포함
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: git checkout KubernetesTools.java
- 예상 소요: 짧음

## 리스크 및 대응
- OCI dependency helm dependency update 시 ECR 인증 필요
  → Chart.yaml 주석에 `aws ecr-public get-login-password | helm registry login` 명령 안내
- Spot 인터럽션 SQS 큐 미설정 시 Karpenter 경고 발생 (기능 동작은 함)
  → values.yaml에 interruptionQueue 빈값 허용, 주석으로 안내
- NodePool `role: app` 라벨이 기존 노드에 없으면 스케줄링 안 됨
  → EC2NodeClass에서 Karpenter가 신규 노드 프로비저닝 시 자동 부착되므로 정상

## 의존성
- Helm 3.8+ (OCI registry 지원)
- EKS 클러스터에 Karpenter controller IAM Role(IRSA) 사전 생성 필요 (AWS 인프라)
- EC2 노드 IAM Instance Profile 사전 생성 필요 (AWS 인프라)
