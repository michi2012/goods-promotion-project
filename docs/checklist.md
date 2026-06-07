# 체크리스트: Karpenter 차트 추가 및 aiops 노드 상태 조회

- 마지막 업데이트: 2026-06-07

## 진행 상황

- [x] 단계 1: helm/promotion-karpenter/ 차트 생성
  - [x] Chart.yaml (karpenter 1.0.8 OCI dependency)
  - [x] values.yaml (clusterName, nodeRole 등 placeholder)
  - [x] templates/nodepool.yaml (Spot+On-demand, role:app, CPU 10/40Gi 상한)
  - [x] templates/ec2nodeclass.yaml (AL2023, karpenter.sh/discovery 태그 기반)
  - [x] 검증 통과 (임시 Chart.yaml로 helm template 렌더링 정상)

- [x] 단계 2: KubernetesTools.java — getClusterStatus() 노드 상태 추가
  - [x] `kubectl get nodes --no-headers` 추가, [Nodes] 섹션 선두 배치
  - [x] @Tool description 반환값 설명 업데이트
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava` — BUILD SUCCESSFUL)

## 최종 검증
- [x] helm template 렌더링 오류 없음 (NodePool·EC2NodeClass 정상 출력)
- [x] gradlew compileJava 오류 없음
- [x] git status: helm/promotion-karpenter/(신규) + KubernetesTools.java(수정)만 변경 확인
- [x] 비범위(aiops 노드 제어 도구, IAM 리소스) 침범 없음

## 발견 사항
- helm template 전체 실행은 ECR 인증 후 helm dependency update 필요 (로컬 불가)
  → 실제 EKS 배포 전 수행
