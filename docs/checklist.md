# 체크리스트: 카나리 에러 격리 고도화 — v2 전용 알람 + 통계적 유의성 체크 + 점진적 자동 승급

- 마지막 업데이트: 2026-06-14

## 진행 상황
- [x] 단계 1: alert-rules.yml `CanaryV2ErrorRateHigh` 알람 추가
  - [x] 검증 통과 (`helm template helm/promotion-monitoring`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: AiOpsAgentService 시나리오10 유의성 체크 가이드 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: KubernetesTools.getCanaryWeight 헬퍼 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileTestJava`, 단위 테스트 추가)
  - [ ] 코드리뷰 통과
- [x] 단계 4: CanaryRolloutScheduler 신규 추가 (+ @EnableScheduling, application.yaml 설정)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 단위 테스트 작성 (CanaryRolloutSchedulerTest 신규, KubernetesToolsTest 보강)
  - [x] 작성 완료 + `.\gradlew.bat :aiops:compileTestJava` 통과
  - [x] `.\gradlew.bat :aiops:test` 실행 결과 확인 (34개 테스트 전체 통과, 0 failed/0 errors)
  - [ ] 코드리뷰 통과
- [x] 단계 6: 최종 검증 (`.\gradlew.bat :aiops:build`, `helm template`, `git diff --stat`)
  - [x] 검증 통과

## 최종 검증
- [x] 모든 단위 테스트 통과 (`.\gradlew.bat :aiops:test`) — 34개 전체 통과
- [x] `helm template helm/promotion-monitoring` 렌더링 성공 (CanaryV2ErrorRateHigh 1건 포함)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (helm/promotion-app, 3-way 카나리, latency 분석 등 미변경)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (10개 파일 수정 + scheduler 패키지 신규 2개 — 모두 plan.md 단계 1~6 범위 내)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `istio_requests_total`의 `destination_service_name` 라벨이 실제 waypoint 메트릭과 다를 경우 단계4 PromQL 수정 필요 (design-notes.md에 기록 후 EKS 배포 시 확인)
