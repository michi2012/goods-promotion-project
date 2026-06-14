# 체크리스트: 카나리(v2) latency 기반 분석 추가

- 마지막 업데이트: 2026-06-14

## 진행 상황
- [x] 단계 1: alert-rules.yml `CanaryV2LatencyHigh` 알람 추가
  - [x] 검증 통과 (`helm template helm/promotion-monitoring`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: AiOpsAgentService 시나리오10 latency 비교 가이드 추가
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: CanaryRolloutScheduler latency 게이트 추가 (+ application.yaml 설정)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: 단위 테스트 작성/보강 (CanaryRolloutSchedulerTest)
  - [x] 검증 통과 (`.\gradlew.bat :aiops:test` — CanaryRolloutSchedulerTest 8/8 통과)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 최종 검증 (`.\gradlew.bat :aiops:build`, `helm template`, `git diff --stat`)
  - [x] 검증 통과

## 최종 검증
- [x] 모든 단위 테스트 통과 (`.\gradlew.bat :aiops:build` exit 0, CanaryRolloutSchedulerTest 8/8)
- [x] `helm template helm/promotion-monitoring` 렌더링 성공 (`CanaryV2LatencyHigh` 1건 포함)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (3-way/상대비교/자체롤백/시나리오10 전면 재작성 없음)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (변경 파일 8개, 모두 plan 범위 내)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `istio_request_duration_milliseconds_bucket` 메트릭이 실제 EKS waypoint에서 노출되는지 확인 필요 (`destination_service_name`과 동일 패턴, EKS 배포 후 확인)
