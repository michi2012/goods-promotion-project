# 체크리스트: Istio Ambient 도입 + AIOps 트래픽 제어 연동

- 마지막 업데이트: 2026-06-06 11:02

## 진행 상황

- [x] 단계 1: Istio Helm 차트 구성 (helm/promotion-istio/)
  - [x] Chart.yaml, values.yaml 작성
  - [x] namespace-label.yaml (promotion 네임스페이스 ambient 레이블)
  - [x] waypoint.yaml (L7 waypoint Gateway)
  - [x] 검증: helm template 렌더링 확인

- [x] 단계 2: promotion-app — version 레이블 + VirtualService + DestinationRule
  - [x] 각 Deployment에 `version: v1` 레이블 추가 (server-a/b/c, gateway, aiops)
  - [x] 서비스별 virtualservice.yaml 추가 (기본 v1:100%)
  - [x] 서비스별 destinationrule.yaml 추가 (subsets v1/v2 + outlier detection 기본값)
  - [x] values.yaml traffic 섹션 추가
  - [x] 검증: helm template 렌더링 확인

- [x] 단계 3: KubernetesTools.java — 신규 도구 2개
  - [x] `proposeTrafficShift` @Tool 추가
  - [x] `proposeOutlierDetectionUpdate` @Tool 추가
  - [x] Slack Block Kit 빌더 메서드 추가
  - [x] 검증: `./gradlew :aiops:compileJava` BUILD SUCCESSFUL

- [x] 단계 4: ActionApprovalService.java — execute 로직
  - [x] `executeTrafficShift` (kubectl patch virtualservice, --patch-file 방식)
  - [x] `executeOutlierDetectionUpdate` (kubectl patch destinationrule)
  - [x] 검증: `./gradlew :aiops:compileJava` BUILD SUCCESSFUL

- [x] 단계 5: SlackInteractiveController — 새 action_id 처리
  - [x] approve/reject_traffic_shift 분기
  - [x] approve/reject_outlier_update 분기
  - [x] 검증: `./gradlew :aiops:compileJava` BUILD SUCCESSFUL

- [x] 단계 6: AIOps RBAC + 시스템 프롬프트
  - [x] rbac.yaml에 networking.istio.io virtualservices/destinationrules get/patch 추가
  - [x] AiOpsAgentService.java 시스템 프롬프트 트래픽 시프트 시나리오 추가
  - [x] 검증: kind 클러스터 E2E 통과

- [x] 단계 7: 문서 업데이트
  - [x] README.md AIOps 섹션 신규 도구 반영
  - [x] docs/arch-snapshot.md Istio + 신규 도구 반영
  - [x] docs/infra-diagram.md Istio 레이어 추가

## 최종 검증
- [x] helm template 렌더링 오류 없음 (version: v1 × 5, DestinationRule × 5, VirtualService × 5 확인)
- [x] `./gradlew :aiops:compileJava` 통과 (BUILD SUCCESSFUL)
- [x] kind 클러스터 E2E 통과: 알람 → AI 분석 → Slack 승인 → kubectl patch 성공 (v1=100%, v2=0%)
- [ ] plan.md 비범위 침범 없음 확인
- [ ] git diff로 의도하지 않은 파일 변경 없음 확인

## 발견 사항
- ObservabilityTools.queryDatabaseHealth 누락된 try-catch → 수정 완료
- executeTrafficShift Windows ProcessBuilder JSON 따옴표 깨짐 버그 → --patch-file 방식으로 수정
- kind 환경에서 ip6tables CONNMARK 커널 모듈 부재로 Istio Ambient CNI 동작 불가 (EKS에서는 무관)
