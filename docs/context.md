# 맥락 노트: 결과 이벤트 유실 시 종료 주문 재대사 (BL-003)

## [완료] BL-003 (PR #35)

## 왜 이 방식을 선택했는가
- 기존 안전망은 "결과가 늦게라도 오면" 처리한다. `SagaTimeoutScheduler`는 EXPIRED 직전에 serverC를 한 번만 조회하고, `OrderReconciliationScheduler`는 PENDING만 보며, 늦은 결제 성공 보상(PR #22)은 `payment-result`를 받아야 실행된다.
- 공백: 타임아웃 조회 시점엔 NOT_PAID → EXPIRED → 이후 serverC가 승인 → `payment-result` CDC 유실. 주문 EXPIRED / 결제 PAID로 고정된다. `payment-cancel` 유실도 같은 증상.
- 해결: serverA가 종료(EXPIRED/FAILED) 주문을 일정 기간 다시 조회해 PAID면 망취소. 기존 `PaymentQueryClient`·Outbox 망취소 경로를 재사용한다.
- 사용자 합의 (2026-11-14): 방식 A(serverA 재대사), 범위 10분~24시간·10분 주기, 주기·하한은 E2E용 프로퍼티, 카운터 `saga.reconcile_cancel.total` 별도.

## 검토했으나 채택하지 않은 대안
### 대안 B: serverC 측 재대사
- 무엇: serverC가 오래된 PAID 결제를 스캔해 serverA에 주문 상태를 묻고, EXPIRED/FAILED면 직접 망취소.
- 왜 안 썼나: serverA 조회 API·serverC 클라이언트·스케줄러가 새로 필요(두 모듈 + 계약 추가). PAID는 대부분의 결제라 스캔 대상이 많다. CDC를 우회하는 장점은 있지만, A도 serverC가 CANCELLED가 될 때까지 다음 주기에 다시 발행하므로 결국 복구된다.

### 대안 C: 감지 + 알람만
- 무엇: 불일치를 카운터로만 알리고 수동 처리.
- 왜 안 썼나: A도 새 메서드 몇 개 수준이라 자동 복구까지 하는 편이 낫다.

### 기존 `compensateLatePaymentSuccess` 재사용
- 왜 안 썼나: 재사용하면 `saga.late_payment_cancel.total`이 함께 올라 "늦은 결과 수신"과 "결과 유실 감지"를 구분할 수 없다. 유실 감지는 CDC 이상 신호라 별도 카운터가 필요.

## 기존 코드베이스 컨벤션
- 스케줄러: `serverA/.../scheduler/` — `@Scheduled(fixedDelay)`, 조회 결과 `PaymentCheckStatus` switch (`OrderReconciliationScheduler`)
- 보상: `SagaOrchestratorService`에서 Outbox 저장 + `meterRegistry.counter(...)` 증가
- 테스트: Mockito 단위 (`OrderReconciliationSchedulerTest`), 리포지토리는 Testcontainers `@DataJpaTest` (`GoodsRepositoryTest`)

## 관련 파일/위치
- `serverA/.../scheduler/OrderReconciliationScheduler.java` — DB 기반 재대사 (PENDING) + 이번에 종료 주문 재대사 추가
- `serverA/.../scheduler/SagaTimeoutScheduler.java` — 타임아웃 직전 1회 조회
- `serverA/.../service/SagaOrchestratorService.java` — `compensateLatePaymentSuccess`, `reconcileToPaid/Failed`
- `serverA/.../client/PaymentQueryClient.java` — serverC 조회. "PAID"만 PAID, CANCELLED 등은 NOT_PAID, 404는 NOT_PAID, 장애는 UNKNOWN
- `serverA/.../repository/OrderRepository.java` — 인덱스 `idx_orders_status_created`
- serverC `PaymentCancelConsumer` — 망취소 후 CANCELLED

## 외부 참조
- `docs/purchase-saga-flow.md` 늦은 결제 성공 보상 섹션
- `docs/design-notes.md` "결과 이벤트가 영영 오지 않는 경우" (PR #22 기록)
