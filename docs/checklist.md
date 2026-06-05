# 체크리스트: K8s 관제 자동화 (AIOps + 대시보드 + 알람)

- 마지막 업데이트: 2026-06-05

## 진행 상황

- [x] 단계 1: aiops Dockerfile + KubernetesTools
  - [x] Dockerfile에 kubectl v1.30.0 설치 레이어 추가
  - [x] KubernetesTools.java 신규 생성 (getClusterStatus, proposeScale)
  - [x] `.\gradlew.bat :aiops:compileJava -x test` 통과

- [x] 단계 2: Slack Block Kit + Interactive 콜백
  - [x] SlackNotificationService.java sendBlockKit() + sendToResponseUrl() 추가
  - [x] SlackInteractiveController.java 신규 생성
  - [x] ActionApprovalService.java reject() + executeScale() 추가
  - [x] `.\gradlew.bat :aiops:compileJava -x test` 통과

- [x] 단계 3: AiOpsAgentService K8s 분석 단계 추가
  - [x] KubernetesTools 주입 및 .tools() 등록
  - [x] 시스템 프롬프트 8번 단계 K8s 분석 추가
  - [x] `.\gradlew.bat :aiops:compileJava -x test` 통과

- [x] 단계 4: K8s RBAC + Gateway 라우팅
  - [x] helm/promotion-app/templates/aiops/rbac.yaml 신규 생성
  - [x] aiops/deployment.yaml serviceAccountName + K8S_NAMESPACE env 추가
  - [x] gateway-service/application-k8s.yml /slack/** 라우트 추가
  - [x] helm template 검증 (ServiceAccount/ClusterRole/ClusterRoleBinding + serviceAccountName 렌더링 확인)

- [x] 단계 5: K8s 알람 룰 추가
  - [x] alert-rules.yml Tier5-Kubernetes 그룹 추가 (5개: CrashLooping, OOMKilled, PodNotReady, NodeNotReady, HPAAtMaxReplicas)
  - [x] helm lint 통과 (YAML 유효성 확인)

- [x] 단계 6: SRE 대시보드 K8s 패널 추가
  - [x] kube-state-metrics Deployment + Service + RBAC 추가 (promotion-monitoring)
  - [x] prometheus.yml kube-state-metrics scrape 추가
  - [x] sre-dashboard.json K8s row + 패널 5개 추가 (Pod 재시작, Deployment 준비율, HPA replica, Pod CPU)
  - [x] JSON 파싱 성공 (39개 패널) + helm lint 0 failed

## 최종 검증
- [x] 모든 aiops 변경 컴파일 통과 (BUILD SUCCESSFUL ×3)
- [x] helm template dry-run 통과 (RBAC 리소스 확인)
- [x] alert-rules.yml YAML 유효성 (helm lint 통과)
- [x] sre-dashboard.json JSON 유효성 (PowerShell ConvertFrom-Json 성공)
- [x] plan.md 비범위 침범 없음 확인

## 발견 사항
- ActionApprovalController의 기존 curl 승인 방식은 유지 (Slack 버튼과 병행)
- Slack 3초 응답 타임아웃 주의: kubectl 실행 비동기 처리 필요
