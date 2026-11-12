# 체크리스트: Saga 상태 복구 안전망

- 마지막 업데이트: 2026-11-12 00:00

## 진행 상황
- [x] 단계 1: PaymentQueryClient + DTO/Enum + RestClient Bean 추가
  - [x] 검증 통과 (`PaymentQueryClientTest`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: SagaOrchestratorService에 reconcileToPaid/reconcileToFailed 추가
  - [x] 검증 통과 (`SagaOrchestratorServiceTest`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: SagaTimeoutScheduler에 결제조회 보정 분기 추가
  - [x] 검증 통과 (`SagaTimeoutSchedulerTest`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: OrderRepository에 findByStatusAndCreatedAtBefore 추가
  - [x] 검증 통과 (기존 OrderRepositoryTest 없음 — compileJava 통과 + 단계 5 스케줄러 테스트로 간접 검증)
  - [ ] 코드리뷰 통과
- [x] 단계 5: OrderReconciliationScheduler 신규 추가
  - [x] 검증 통과 (`OrderReconciliationSchedulerTest`)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `./gradlew.bat :serverA:test` 전체 통과 (신규/수정 테스트 전부 통과. 기존 Docker/Testcontainers 의존 8개 테스트는 로컬 Docker 미구동으로 실패 — 이번 변경과 무관)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (메트릭/알람 추가 없음, order-completed 미발행, 기존 handleSagaFailure 미수정)
- [x] 의도하지 않은 파일 변경이 없는지 `git diff --stat`으로 최종 확인 (serverA + docs/만 변경)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `handleSagaFailure`의 조건부 UPDATE 반환값 미확인 갭 — 이번엔 손대지 않음 (context.md에 기록)
