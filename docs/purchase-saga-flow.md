# 구매-Saga 흐름 (serverA / serverB / serverC)

이 프로젝트의 핵심 흐름. 클라이언트의 구매 요청 한 건이 serverA(Saga 오케스트레이터) → Kafka → serverB(조회 뷰)·serverC(결제)로 퍼졌다가, 다시 serverA로 결과가 모여 최종 확정되는 과정을 클래스·메서드 단위로 따라간다. 설계 이유·트레이드오프는 [README.md 핵심 설계 결정](../README.md#핵심-설계-결정)을 참고. 여기서는 "코드가 실제로 어떻게 움직이는지"만 다룬다.

## 진입점

| 진입점 | 모듈 | 트리거 | 처리 클래스 |
|---|---|---|---|
| `POST /api/v1/promotions/purchase` | serverA | 클라이언트 구매 요청 | `PromotionController.purchase()` |
| Kafka `purchase_events` 구독 | serverA | 위 요청이 발행한 이벤트 | `PurchaseKafkaConsumer.consume()` |
| Kafka `payment-request` 구독 | serverC | serverA Outbox→CDC | `PaymentKafkaConsumer.consume()` |
| Kafka `order-status-update` 구독 | serverB | serverA Outbox→CDC | `OrderStatusKafkaConsumer.consume()` |
| Kafka `payment-result` / `status-update-result` 구독 | serverA | serverC/serverB 처리 결과 | `SagaResultConsumer` |
| `@Scheduled(fixedDelay=30_000)` | serverA | 30초 주기 | `SagaTimeoutScheduler.checkTimeouts()` |
| `GET /api/v1/orders/{orderId}/status` | serverB | 클라이언트 폴링 (프론트엔드) | `OrderQueryController.getOrderStatus()` |

## 1. 구매 접수 — `PromotionController` → `PromotionService.acceptPurchase()` (serverA, 동기)

1. `PromotionController.purchase()` — UUID v7(`Generators.timeBasedEpochGenerator()`)로 `orderId` 채번 후 `promotionService.acceptPurchase()` 호출. 이 시점에 DB 접근 없음.
2. `PromotionService.acceptPurchase()`가 Redis 3단계를 순서대로 통과시킨다. 하나라도 막히면 즉시 예외를 던지고 이후 단계는 실행되지 않는다.
   1. `RedisStockService.isKnownSoldOut(goodsId)` — Caffeine 로컬 캐시(TTL 60s) 확인. `true`면 `SoldOutException` (Redis 왕복 없음).
   2. `RedisStockService.tryMarkUserPurchased(userId, goodsId)` — `SETNX user:purchase:{userId}:{goodsId}` (TTL 1시간). 이미 있으면 `DuplicateOrderException`.
   3. `RedisStockService.reserveStock(goodsId, quantity)` — Lua 스크립트로 `GET → 비교 → DECRBY` 원자 실행. 반환값 `-2`(키 없음: 재고 미초기화, RuntimeException) / `-1`(재고 부족: `SoldOutException`, 이때 앞서 마킹한 `tryMarkUserPurchased`를 `releaseUserPurchase()`로 되돌림) / `≥0`(선점 성공, 남은 재고).
3. 선점 성공 시 `publishStockSnapshot(goodsId)` — 현재 재고를 `stock-snapshot` 토픽에 **직접**(Outbox 아님) 발행. 실패해도 무시(로그만).
4. `kafkaTemplate.send("purchase_events", userId, payload)` — **직접** 비동기 발행. `.whenComplete()` 콜백에서 실패 시 `rollbackRedis()`(재고 INCRBY + 유저 마킹 삭제)를 실행. 직렬화(`ObjectMapper`) 자체가 실패하면 동기적으로 롤백 후 예외를 던진다.
5. 컨트롤러는 Kafka 발행 완료를 기다리지 않고 즉시 `202 Accepted` + `{orderId, "처리 대기중입니다..."}` 반환.

## 2. Saga Phase 1 — 주문 저장 (serverA, `purchase_events` 소비)

1. `PurchaseKafkaConsumer.consume()` — payload 역직렬화 후 `OrderCommandService.saveOrderAndDecreaseStock()` 호출.
2. `OrderCommandService.saveOrderAndDecreaseStock()` (`@Transactional`) — `orderRepository.findByOrderId()`로 이미 처리된 메시지인지 먼저 확인(Kafka 재전송 대비 멱등). 없으면 `processNewOrder()`:
   - **금액 계산** — `goodsRepository.findById()`로 DB 가격을 읽어 `totalAmount = unitPrice × quantity`(`Math.multiplyExact`) 계산. 클라이언트는 금액을 보내지 않는다(위변조 방지). 구매 접수(1단계)는 Redis만 거치므로 가격 조회는 여기서 한다.
   - `Order` 엔티티 저장 (status=`PENDING`, `unitPrice`/`totalAmount`/`currency=KRW` 스냅샷 — 이후 상품 가격이 바뀌어도 이 주문 금액은 고정)
   - `goodsRepository.decreaseStock()` — DB 재고도 차감(Redis는 이미 선점됨. DB는 조회/정산용 원장)
   - `SagaStateService.initSagaState()` — Redis Hash `saga:state:{orderId}`에 `orderEntityId/userId/goodsId/quantity/statusUpdateCompleted=false/paymentCompleted=false/failed=false/createdAt` 저장
   - `OutboxEventService.save()` 2회 — `order-status-update`(status=PENDING), `payment-request`(`PaymentRequestMessage` — 구매 정보 + `unitPrice`/`totalAmount`/`currency`) — **같은 트랜잭션 안에서 Outbox 테이블에 INSERT만** 하고 끝. 실제 Kafka 발행은 Debezium CDC가 `outbox_event` 테이블 binlog를 감지해서 수행(직접 `kafkaTemplate.send()` 호출 없음).
   - **가격 0원(가격 미설정 기존 상품) 또는 상품 없음** — `payment-request`를 저장하지 않고 `SagaOrchestratorService.handleSagaFailure(orderId, "INVALID_PRICE")`로 기존 보상(FAILED + Redis·DB 재고·유저마킹 복구)을 그대로 탄다. 0원 결제가 PG로 나가지 않게 막는 장치.

## 3. 병렬 처리 — 결제(serverC)와 상태 갱신(serverB)

Debezium이 두 Outbox 레코드를 각각 Kafka로 실어나르면, serverC와 serverB가 독립적으로 소비한다.

### 3-A. serverC — 결제 처리
1. `PaymentKafkaConsumer.consume()` — `ConsumerRecord`에서 `traceparent` 헤더를 꺼내 분산 트레이스 Child Span을 직접 구성(`buildChildSpan`)한 뒤 `PaymentService.processPayment()` 호출.
2. `PaymentService.processPayment()` (`@Transactional`):
   - `paymentRepository.claimOrder(msg)` — Unique 제약 기반 원자적 클레임(`amount`/`currency` 함께 INSERT). 실패(중복 수신)면 즉시 반환(멱등).
   - **금액 없는 옛 메시지**(배포 중 옛 serverA가 보낸 `payment-request`) — 금액 검증 없이 기존 방식으로 처리하고 `business_payment_legacy_without_amount_total` 증가 (확장-축소 패턴 1단계).
   - `pgClient.processPayment(msg)` 호출 — 구현체는 `MockPgClient`. `@CircuitBreaker(name="pgClientCb")`로 감싸져 있음. 반환값 `PgTransactionResult(status, approvedAmount)` — 승인 여부와 PG가 실제 승인한 금액.
     - `MockPgClient.processPayment()` — 500ms 지연 시뮬레이션 후 1% 확률로 거절, 승인 시 요청 금액 그대로 승인. 애매한 응답 뒤 조회(`queryTransaction`)도 승인 금액을 돌려주며, 조회로 확정된 승인 건도 같은 금액 검증을 거친다.
   - **승인 금액 검증** — 승인됐는데 `approvedAmount ≠ totalAmount`(또는 PG가 승인 금액을 안 돌려줌)이면 `pgClient.cancelPayments()`로 **즉시 망취소** + `FAILED` + `payment-result(false, "금액 불일치")` + `business_payment_error_total{type=amount_mismatch}`. serverA의 `payment-cancel`은 "결제 성공" 건에만 발행되므로 이 경우는 serverC가 직접 취소하고, serverA는 실패 결과를 받아 기존 보상을 탄다.
     - 서킷 OPEN 시 `fallbackProcessPayment()`가 `PgPaymentException`을 던진다. `PaymentService`는 이를 **re-throw하지 않고** `paymentRepository.updateOrderStatus(FAILED)` + `payment-result(success=false)` Outbox 저장 후 정상 반환 — 트랜잭션을 커밋시켜 Saga 보상이 정상 트리거되도록 의도적으로 설계됨.
   - PG가 정상 응답한 경우: `updateOrderStatus(PAID 또는 FAILED)` + `payment-result` Outbox 저장 (Outbox+CDC 방식, 직접 발행 아님).
   - 비즈니스 카운터: `business_payment_attempts_total`/`_success_total`/`_error_total{type=pg_rejection|pg_system_error|amount_mismatch}`, `business_payment_legacy_without_amount_total`.

### 3-B. serverB — 상태 뷰 갱신
1. `OrderStatusKafkaConsumer.consume()` → `OrderStatusEventHandler.handleStatusUpdate()`.
2. `OrderQueryService.updateOrderStatus()` — Redis String `order:view:{orderId}:status`에 상태값 저장(DB 없음, Redis가 유일한 저장소).
3. Redis 갱신이 끝나면(PENDING 상태 메시지에 한해), `status-update-result` 토픽에 **직접**(Outbox 아님) 동기 발행 — `kafkaTemplate.send(...).get(3, TimeUnit.SECONDS)`로 완료를 기다린다.
   - Redis 갱신 자체가 실패하면 `false` 결과를 발행(best-effort)하고 종료.
   - 발행 자체가 실패(타임아웃 등)하면 예외가 전파되어 Kafka 컨슈머 ErrorHandler의 재시도 → DLT로 넘어간다.

## 4. Saga 결과 판정 — `SagaResultConsumer` (serverA)

`payment-result`와 `status-update-result` 두 토픽을 각각 별도 `@KafkaListener` 메서드로 구독한다. 어느 쪽이 먼저 와도 동일하게 처리:

1. 메시지의 `success`가 `false`면 즉시 `SagaOrchestratorService.handleSagaFailure(orderId, errorMessage)` 호출하고 종료.
2. `success`가 `true`면 먼저 `SagaStateService.exists(orderId)`로 Saga가 아직 진행 중인지 확인한다. **Saga 상태가 이미 없으면(종료·삭제됨) 플래그를 기록하지 않는다** — 없는 키에 HSET하면 `createdAt` 없는 좀비 키가 생겨 타임아웃 스캐너가 영원히 건너뛰기 때문이다.
   - `payment-result(success)`가 늦게 온 경우 → `SagaOrchestratorService.compensateLatePaymentSuccess()` (아래 4-C).
   - `status-update-result(success)`가 늦게 온 경우 → 로그만 남기고 무시.
3. 진행 중이면 `markStatusUpdateCompleted()` 또는 `markPaymentCompleted()`로 Redis Hash 플래그를 세우고, **두 플래그가 모두 true**가 된 순간(먼저 도착한 메시지는 `false` 반환, 나중 메시지가 `true` 반환)에만 `SagaOrchestratorService.tryCompleteSaga(orderId)`를 호출한다.

### 4-A. 성공 확정 — `tryCompleteSaga()`
1. `SagaStateData` 조회 — 없으면(이미 삭제됨) 또는 `isFailed()`가 true면 즉시 반환.
2. `orderRepository.updateStatusIfPending(orderId, PAID)` — **조건부 UPDATE**(WHERE status=PENDING)로 멱등성 보장. 영향받은 row가 0이면(이미 처리됨) `SagaState`만 지우고 종료.
3. Redis에 저장해둔 `createdAt`과 현재 시각의 차를 `business_payment_e2e_duration_seconds` 타이머에 기록 — README의 "결제 종단 간 p95 지연" 지표의 원본.
4. `order-status-update`(PAID), `order-completed` — Outbox 저장(같은 트랜잭션).
5. `SagaStateService.deleteSagaState()`로 Redis Hash 삭제.
6. `order-completed`는 serverC의 `OrderCompletedConsumer`가 소비 — 별도 처리 없이 Saga 최종 확정 로그만 남긴다(트레이스 Span 연결 목적).

### 4-B. 실패 보상 — `handleSagaFailure(orderId, reason)`
1. `SagaStateService.markFailedAndCheck()` — Redis `HINCRBY failedOnce`로 최초 1회만 `true` 반환(중복 실행 방지, 타임아웃 스케줄러와의 동시 호출도 이걸로 막힘).
2. `isPaymentCompleted()`를 **SagaState 삭제 전에** 미리 읽어 결제 성공 여부를 기억해둔다.
3. `reason=="TIMEOUT"`이면 `OrderStatus.EXPIRED`, 그 외는 `FAILED`로 `updateStatusIfPending()`.
4. 보상 3종: `RedisStockService.releaseStock()`(재고 INCRBY) + `releaseUserPurchase()`(중복방어 키 삭제) + `goodsRepository.increaseStockAtomically()`(DB 재고 복구).
5. 결제가 이미 성공했던 경우에만 `payment-cancel` Outbox 저장 — PG 망취소 트리거.
6. `stock-snapshot`(복구된 재고 반영), `order-status-update`(FAILED/EXPIRED) Outbox 저장.
7. `SagaState` 삭제.
8. serverC `PaymentCancelConsumer`가 `payment-cancel`을 소비해 `pgClient.cancelPayments()` 호출 후 결제 상태를 `CANCELLED`로 변경.

> 5번의 "결제 성공 여부"는 **그 시점의 Saga 플래그**로만 판단한다. 실패 확정 뒤 결제 성공이 늦게 도착하면 여기서는 놓치므로 4-C가 따로 보상한다.

### 4-C. 늦게 도착한 결제 성공 보상 — `compensateLatePaymentSuccess(orderId)`
Saga가 끝난(상태 삭제) 뒤 `payment-result(success)`가 오는 경우. 처리 중(PROCESSING) 타임아웃, DB 재대사 `reconcileToFailed` 이후, 결과 이벤트 지연 등에서 생긴다.
1. `orderRepository.findByOrderId()`로 주문 상태 확인.
2. **EXPIRED/FAILED** → 고객 결제만 승인된 채 남아 있으므로 `payment-cancel` Outbox 저장(망취소) + `saga.late_payment_cancel.total` 증가.
3. **PAID** → 중복 수신이라 무시. **PENDING** → Saga 상태만 유실된 경우라 DB 재대사(`OrderReconciliationScheduler`)에 맡긴다. 주문 없음 → 경고 로그.

### 4-D. 결과 이벤트 유실 재대사 — `OrderReconciliationScheduler.reconcileTerminatedOrders()`
4-C는 `payment-result(success)`를 **받아야** 실행된다. 늦은 결과나 `payment-cancel`이 CDC에서 유실되면 주문 EXPIRED/FAILED + serverC PAID로 고정되므로, 이벤트를 기다리지 않고 직접 조회한다.
1. `@Scheduled(fixedDelayString="${saga.reconcile.terminated.interval-ms:600000}")` — 기본 10분마다 생성 후 `min-age-minutes`(기본 10분)~24시간 지난 EXPIRED/FAILED 주문을 조회(`findByStatusInAndCreatedAtBetween`).
2. 주문마다 `PaymentQueryClient.checkPaymentStatus()`:
   - `PAID` → `compensateLostPaymentResult()` — 주문이 EXPIRED/FAILED면 `payment-cancel` Outbox 저장 + `saga.reconcile_cancel.total` 증가 (4-C와 카운터 구분: CDC 이상 신호).
   - `NOT_PAID`(미결제·CANCELLED) → 아무것도 안 함. 망취소가 끝나면 이 경로로 자연히 대상에서 빠진다.
   - `UNKNOWN` → 다음 주기 재조회.

## 5. 타임아웃 처리 — `SagaTimeoutScheduler`

- `@Scheduled(fixedDelay=30_000)` — 30초마다 `saga:state:*` 패턴을 `SCAN`(count=100)으로 순회.
- 이미 `failed`인 항목, `createdAt`이 없는 항목은 스킵. `createdAt` 대비 경과시간이 `TIMEOUT_MS=3*60*1000`(**3분**)을 넘으면 EXPIRED 처리 전에 `PaymentQueryClient.checkPaymentStatus()`로 serverC 실제 결제 상태를 조회한다.
  - `PAID` → `reconcileToPaid()` (EXPIRED 대신 PAID 보정).
  - `NOT_PAID` → `handleSagaFailure(orderId, "TIMEOUT")` + `saga.expired.total` 증가.
  - `UNKNOWN`(serverC 인스턴스 미발견·조회 실패) → **판단하지 않고 건너뛴다.** Saga 상태를 그대로 두고 다음 30초 주기에 재조회 + `saga.timeout.payment_unknown` 증가. EXPIRED로 처리하면 Saga 플래그에 결제완료가 없어 실제 승인된 결제가 망취소 없이 남을 수 있기 때문이다. 상한 전에 serverC가 복구되면 밀린 결제가 처리되어 정상 경로로 확정된다.
  - 단, Saga 생성 후 **30분**(`saga.timeout.payment-unknown-max-minutes`)이 지나도 UNKNOWN이면 재고 묶임을 끝내기 위해 `handleSagaFailure(orderId, "TIMEOUT")` → **EXPIRED** + 재고 복구, `saga.timeout.payment_unknown_expired`·`saga.expired.total` 증가. serverC에서 실제 승인된 결제는 복구 후 늦은 결제 성공 보상 또는 종료 주문 재대사(24시간)가 망취소한다. 알람: P2 `PaymentQueryUnknownPersisting`(UNKNOWN 5분 지속), P1 `SagaForceExpiredOnUnknown`(강제 만료 발생).

## 6. DLT (Dead Letter) 처리

- `purchase_events.DLT` — `PurchaseDltConsumer.consume()`이 원본 payload를 파싱해 `orderId/goodsId/quantity`를 추출(실패 시 `orderId="UNKNOWN"`으로 원문 보존)하고 `DeadLetterService.saveDeadLetter()`(`REQUIRES_NEW`로 격리 저장)로 `dead_letter` 테이블에 적재.
- 관리자 API(`AdminController`, serverA):
  - `GET /api/v1/admin/dlt` — `UNRESOLVED` 전체 조회 (aiops `DltTools.listUnresolvedDlt` 전용)
  - `GET /api/v1/admin/dlt/orders/{orderId}` — 단건 조회 (cs-bot 내부 호출)
  - `POST /api/v1/admin/dlt/{dltId}/retry` — `DeadLetterService.retryDeadLetter()`가 `goodsRepository.increaseStockAtomically()`로 재고를 강제 복구하고 DLT를 `RESOLVED`로 마킹. 이미 `RESOLVED`면 `AlreadyResolvedDltException`.
- `payment-cancel.DLT` — serverC `PaymentCancelConsumer.handleDlt()`가 `business_payment_pg_refund_fatal_total` 카운터만 증가시키고 로그 경고(PG 망취소까지 실패한 치명 케이스 — 수동 정산 대상, README P0 알람 대상).

## 7. 클라이언트 조회 — `OrderQueryController` (serverB)

- `GET /api/v1/orders/{orderId}/status` → `OrderQueryService.getOrderStatus()` — Redis `order:view:{orderId}:status` 조회, 키 없으면 `"NOT_FOUND"` 문자열 반환(예외 아님).
- `GET /api/v1/goods/{goodsId}/stock` → `OrderQueryService.getStockView()` — Caffeine 캐시(재고 0이면 5초, 판매중이면 500ms TTL, `Expiry` 커스텀 구현) 우선 조회 후 Redis 폴백.
- 프론트엔드는 이 API를 버튼 클릭 시 1회 호출하는 단순 조회 UI다. README에 서술된 Exponential Backoff 폴링 정책은 실제 프론트엔드 코드에는 없다 — 자세한 내용과 근거는 [`docs/frontend-flow.md`](frontend-flow.md) 참고.
