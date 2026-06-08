# 체크리스트: 결제 흐름 종단 간 p95 지연 알림 규칙 구현 (MIC-8)

- 마지막 업데이트: 2026-06-08

## 진행 상황
- [x] 단계 1: `business_payment_e2e_duration_seconds` 종단 간 지연 타이머 계측 추가
  - [x] 검증 통과 (`./gradlew :serverA:test --tests SagaOrchestratorServiceTest` — 사용자 실행 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: `PaymentE2ELatencyHigh` 알림 규칙 추가 (helm + 로컬 alert-rules.yml 동기화)
  - [x] 검증 통과 (`helm template helm/promotion-monitoring -f helm/promotion-monitoring/values.yaml | grep PaymentE2ELatencyHigh` → 렌더링 출력 확인)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `./gradlew :serverA:test` 전체 통과 (사용자 실행 확인)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (`git diff --stat` 검토 완료)
- [x] 의도하지 않은 파일 변경이 없는지 `git diff`로 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 진행하며 기록)
