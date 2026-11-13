# 맥락 노트: Saga 안전망 공백 수정

## 왜 이 방식을 선택했는가
결제 금액 기본기 작업의 서버 간 흐름 검증 중, serverC에는 PAID 30,000원이 남았는데 주문은 EXPIRED이고 망취소가 나가지 않은 사례가 재현됐다(order 01a1ce44…). 원인은 두 갈래다.

1. `SagaTimeoutScheduler`(#20)가 serverC 결제조회 결과 UNKNOWN(인스턴스 미발견·조회 실패)을 NOT_PAID와 똑같이 EXPIRED로 처리한다. `handleSagaFailure`는 Redis Saga 상태의 `paymentCompleted=true`일 때만 `payment-cancel`을 보내는데, 결과 이벤트가 아직 안 왔으면 이 플래그가 없다.
2. 실패 확정(Saga 상태 삭제) 뒤 `payment-result(success)`가 늦게 오면 `markPaymentCompleted()`가 삭제된 키에 HSET해 `createdAt` 없는 좀비 키를 되살리고, `tryCompleteSaga()`는 상태 불완전으로 반환한다 — 망취소 없음. 좀비 키는 타임아웃 스캐너가 `createdAt=0`이라 영원히 건너뛴다. 처리 중(PROCESSING) 타임아웃, DB 재대사 `reconcileToFailed` 뒤에도 같은 경로가 생긴다.

사용자 결정 (2026-11-12):
- **UNKNOWN → 건너뛰고 다음 주기 재시도.** 모르는 상태에서 판단하지 않는다. `OrderReconciliationScheduler`가 이미 같은 방식(UNKNOWN 재시도)이라 일관된다.
- **늦은 결제 성공 보상 포함.** 결과 컨슈머에서 Saga 상태가 없으면 플래그를 찍지 않고, 주문이 EXPIRED/FAILED면 `payment-cancel`을 보낸다. 늦은 성공이 생기는 모든 경로(UNKNOWN 이후·처리 중 타임아웃·DB 재대사 실패)를 한 곳에서 막는다.

## 검토했으나 채택하지 않은 대안
### 대안 A: UNKNOWN이면 EXPIRED + 무조건 망취소
- 왜 안 썼나: 결제가 아직 처리 전이면 취소가 먼저 도착하고 결제가 나중에 승인되는 경쟁이 생긴다(serverC는 취소 대상 행이 없으면 0건 갱신 후 끝, 이후 결제 진행). 막으려면 serverC에 취소 선기록·결제 차단 로직이 필요해 범위가 커진다.

### 대안 B: `PaymentQueryClient`가 PROCESSING을 UNKNOWN으로 분류
- 왜 안 썼나: 처리 중 타임아웃은 늦은 성공 보상으로 커버된다. serverA·serverC 조회 계약 변경까지 필요해 이번 범위에서 제외.

### 대안 C: Saga 플래그 기록을 Lua로 원자화(키 있을 때만 HSET)
- 왜 안 썼나: `exists` 확인과 HSET 사이 경쟁 창이 매우 짧고, 발생해도 결과가 기존 동작(좀비 키)과 같다. 과설계로 판단.

## 기존 코드베이스 컨벤션
- 스케줄러: `serverA/.../scheduler/SagaTimeoutScheduler.java`, `OrderReconciliationScheduler.java` (`switch` on `PaymentCheckStatus`)
- 보상: `SagaOrchestratorService` `@Transactional` 메서드 + Outbox 저장 (`handleSagaFailure`, `reconcileToPaid/Failed`)
- 메트릭: `meterRegistry.counter("saga.expired.total")` 패턴
- 테스트: `SagaTimeoutSchedulerTest`, `SagaOrchestratorServiceTest`(Mockito), `SagaStateServiceTest`

## 관련 파일/위치
- `serverA/.../scheduler/SagaTimeoutScheduler.java` — 타임아웃 + 결제조회
- `serverA/.../kafka/SagaResultConsumer.java` — `payment-result`/`status-update-result` 수신
- `serverA/.../service/SagaOrchestratorService.java` — 완료·실패·보정·늦은 성공 보상
- `serverA/.../service/SagaStateService.java` — Redis Saga 상태
- `serverA/.../client/PaymentQueryClient.java` — PAID/NOT_PAID/UNKNOWN 분류 (변경 없음)
- `serverC/.../kafka/PaymentCancelConsumer.java` — 망취소 (변경 없음)

## 외부 참조
- 없음
