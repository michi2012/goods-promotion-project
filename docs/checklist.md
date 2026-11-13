# 체크리스트: Saga 안전망 공백 수정

- 마지막 업데이트: 2026-11-12

## [완료] UNKNOWN 재시도 + 늦은 결제 성공 보상

### 진행 상황
- [x] 단계 1: 타임아웃 시 결제조회 UNKNOWN이면 건너뛰고 재시도
  - [x] 검증 통과 (`SagaTimeoutSchedulerTest` 5건)
- [x] 단계 2: `SagaStateService.exists` + `compensateLatePaymentSuccess`
  - [x] 검증 통과 (`SagaOrchestratorServiceTest` 14건, `SagaStateServiceTest` 8건)
- [x] 단계 3: `SagaResultConsumer` Saga 상태 없음 분기
  - [x] 검증 통과 (`SagaResultConsumerTest` 5건 신규, `OrderReconciliationSchedulerTest` 4건 회귀 확인)
- [x] 단계 4: 로컬 재현 검증
  - [x] 재현 1: serverC 중단 중 구매(01a1ce5b…) → 15:07:47~15:10:47 UNKNOWN 재시도 7회, EXPIRED 0건, PENDING 유지 → serverC 복구 후 정상 경로(Saga 최종 확정)로 PAID 30,000 / payments PAID 30,000
  - [x] 재현 2: EXPIRED 주문(01a1ce44…)에 늦은 payment-result(success) → payment-cancel(15:03:07) → serverC CANCELLED, Redis 좀비 키 없음
- [x] 단계 5: 문서 (purchase-saga-flow 4·4-C·5절, arch-snapshot 안전망 다이어그램·스케줄러 표, design-notes "Saga 타임아웃" 섹션 수치 정정 + 결정 추가)

### 최종 검증
- [x] 수정·추가한 serverA 테스트 모두 통과 (Saga 관련 36건)
- [x] `gradlew :serverA:test` 82건 중 80건 통과 (실패 2건 = 기존 `OpenApiContractTest` — 후속 1번 작업)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 이후 작업
- [ ] 1번: serverA springdoc 추가 + `OpenApiContractTest` 통과 (새 의존성 — 승인 필요)
- [ ] 1·2번 완료 후 전체 E2E(화면 + 서버 간 흐름) 재확인 → PR

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 
