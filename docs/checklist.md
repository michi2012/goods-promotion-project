# 체크리스트: aiops 인프라 챗봇 — 라벨 컨벤션 반영 + 인프라 도구 화이트리스트 추가

- 마지막 업데이트: 2026-06-15

## 진행 상황
- [x] 단계 1: ObservabilityTools 라벨 컨벤션 description 수정
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava :aiops:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 2: InfraChatAgentService SYSTEM_PROMPT — 라벨 컨벤션 섹션 + 신규 도구 가이드
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava :aiops:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 3: KubernetesTools — QUERY 도구 2종 추가 (Pod 로그 조회, 롤아웃 상태/이력 조회)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava :aiops:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 4: Pod 재시작 ACTION 추가 (KubernetesTools + ActionApprovalService + SlackInteractiveController)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava :aiops:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 5: HPA minReplicas ACTION 추가 (KubernetesTools + ActionApprovalService + SlackInteractiveController)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava :aiops:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 6: KubernetesToolsTest — 신규 단위 테스트 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test --tests "aiops.aiops.tools.KubernetesToolsTest"` → 통과, 사용자 실행)
  - [ ] 코드리뷰 통과
- [x] 단계 7: 전체 빌드 검증
  - [x] 검증 통과 (`.\gradlew.bat :aiops:build` → 통과, 사용자 실행)

## 최종 검증
- [x] 모든 단위 테스트 통과 (`.\gradlew.bat :aiops:test`)
- [x] 전체 빌드 통과 (`.\gradlew.bat :aiops:build`)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (getClusterStatus 실패 응답 미변경, Kafka/Alertmanager/DB KILL 도구 미추가, CodebotAgentService SYSTEM_PROMPT 미변경, 기존 proposeHpaPatch/executeHpaPatch/approve_hpa_patch 미변경)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 진행 중 발견 시 기록)
