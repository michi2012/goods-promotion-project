# 계획서: Saga 안전망 공백 수정 — 결제조회 UNKNOWN 재시도 + 늦게 도착한 결제 성공 보상

- 작성일: 2026-11-12
- 관련 이슈/티켓: 없음 (결제 금액 기본기 최종 검증 중 로컬 재현 — order 01a1ce44…: serverC PAID 30,000원인데 주문 EXPIRED, 망취소 없음)
- 브랜치: `feature/payment-amount` (후속 작업)

## 목표
고객 결제가 승인됐는데 주문만 실패(EXPIRED/FAILED)로 끝나고 망취소가 나가지 않는 경로를 막는다. 결제 상태를 모르면 판단을 미루고, 실패 확정 뒤 결제 성공이 늦게 오면 망취소를 보낸다.

## 성공 기준
- [ ] Saga 타임아웃 시 결제조회 UNKNOWN이면 EXPIRED 처리하지 않고 다음 주기로 미룬다 (`SagaTimeoutSchedulerTest`)
- [ ] 주문이 EXPIRED/FAILED인 상태에서 `payment-result(success)`가 오면 `payment-cancel`을 Outbox에 저장한다. PAID/PENDING이면 보내지 않는다 (`SagaOrchestratorServiceTest`)
- [ ] Saga 상태가 이미 삭제된 주문의 결과 이벤트가 와도 Redis Saga 키를 다시 만들지 않는다 (`SagaResultConsumerTest`)
- [ ] 로컬 재현 1: serverC를 내린 채 구매 → 3분 타임아웃 지나도 PENDING 유지 → serverC 복구 후 PAID
- [ ] 로컬 재현 2: EXPIRED 주문(01a1ce44…)에 늦은 `payment-result(success)` 발행 → `payment-cancel` → serverC 결제 CANCELLED

## 비범위 (Out of Scope)
- 결과 이벤트가 영영 오지 않는 경우(CDC 유실) — serverC 측 재대사 등 별도 장치 필요
- `PaymentQueryClient`의 `PROCESSING` 구분 (늦은 성공 보상으로 커버)
- serverC `PaymentCancelConsumer` 변경 (중복 취소 멱등성은 기존 동작 유지)
- UNKNOWN 장기화 시 최대 대기 시간·알람

## 단계별 작업 계획

### 단계 1: 타임아웃 시 UNKNOWN이면 재시도
- 변경 파일: `serverA/.../scheduler/SagaTimeoutScheduler.java`, `SagaTimeoutSchedulerTest.java`
- 변경 내용 요약: 결제조회 결과 PAID → `reconcileToPaid`(기존), NOT_PAID → `handleSagaFailure("TIMEOUT")`(기존), **UNKNOWN → 경고 로그 + `saga.timeout.payment_unknown` 카운터 후 건너뜀**. Saga 상태는 그대로 두어 다음 30초 주기에 재조회.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.scheduler.SagaTimeoutSchedulerTest"`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 2: 늦은 결제 성공 보상 로직
- 변경 파일: `serverA/.../service/SagaStateService.java`, `SagaOrchestratorService.java`, `SagaStateServiceTest.java`, `SagaOrchestratorServiceTest.java`
- 변경 내용 요약: `SagaStateService.exists(orderId)` 추가. `SagaOrchestratorService.compensateLatePaymentSuccess(orderId)` 추가 — 주문이 EXPIRED/FAILED면 `payment-cancel` Outbox 저장 + `saga.late_payment_cancel.total` 카운터, PAID면 무시(중복), PENDING이면 무시(DB 재대사가 처리), 주문 없음이면 경고.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.service.SagaOrchestratorServiceTest" --tests "promotion.serverA.service.SagaStateServiceTest"`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 보통

### 단계 3: 결과 컨슈머에서 Saga 상태 없음 분기
- 변경 파일: `serverA/.../kafka/SagaResultConsumer.java`, `SagaResultConsumerTest.java`(신규)
- 변경 내용 요약: 성공 결과 수신 시 Saga 상태가 없으면 플래그를 찍지 않는다(좀비 키 방지). `payment-result(success)`면 `compensateLatePaymentSuccess` 호출, `status-update-result(success)`면 로그만 남기고 무시.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.kafka.SagaResultConsumerTest"`
- 롤백 방법: `git checkout --` 해당 파일 + 신규 테스트 삭제
- 예상 소요: 짧음

### 단계 4: 로컬 재현 검증 (serverA 재배포)
- 변경 파일: 없음
- 변경 내용 요약: serverA 재빌드·재기동 후 성공 기준의 로컬 재현 1·2 수행. 결과를 DB·로그로 확인.
- 검증 방법: orders/payments/outbox 조회, serverA·serverC 로그
- 롤백 방법: 해당 없음 (검증 단계)
- 예상 소요: 김 (타임아웃 3분 대기 포함)

### 단계 5: 문서
- 변경 파일: `docs/purchase-saga-flow.md`, `docs/arch-snapshot.md`, `docs/design-notes.md`(조건 충족 시)
- 변경 내용 요약: 타임아웃 UNKNOWN 재시도, 늦은 결제 성공 보상, 좀비 키 방지 반영.
- 검증 방법: 육안 확인
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: serverC 장애가 길면 주문이 PENDING으로 오래 남고 재고가 묶임 → 대응: 카운터로 관측, 장애 해소 시 자동 해소. 최대 대기·알람은 후속
- 리스크: 늦은 성공이 중복 수신되면 `payment-cancel`이 두 번 나감 → 대응: serverC 취소는 상태 무관하게 CANCELLED로 덮어쓰는 기존 동작(모의 PG 멱등). 실제 PG 중복 취소 응답 처리는 비범위로 기록
- 리스크: `exists` 확인과 플래그 기록 사이에 상태가 삭제되는 경쟁 → 대응: 창이 매우 짧고, 발생해도 결과는 기존 동작과 동일(좀비 키). 원자 처리(Lua)는 과설계로 보고 제외

## 의존성
- 기존 Saga 흐름(`handleSagaFailure`, `reconcileToPaid`), Outbox + Debezium(`payment-cancel`)
- 로컬 재현: Kafka Connect·Debezium, serverC, Eureka(discovery)
- 신규 Gradle 의존성·마이그레이션 없음
