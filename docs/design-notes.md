# Design Notes
> 코드만 봐서는 알 수 없는 설계 결정과 제약을 기록한다.
> 구현 완료 후 비자명한 결정이 있을 때만 추가한다. 자동 생성 금지.

---

## 재고 동시성 제어 (3단계 방어)

**결정:** DB 레벨 락 없이 Redis 3단계로 동시성 제어

**1단계 — Caffeine 로컬 캐시 (품절 캐시)**
- `RedisStockService.isKnownSoldOut()`: Redis 왕복 없이 메모리에서 즉시 품절 차단
- 만료: 60초. 재고가 복구돼도 최대 60초 동안 차단 유지 (의도된 트레이드오프)

**2단계 — Redis SETNX (중복 구매 방어)**
- 키: `user:purchase:{userId}:{goodsId}`, TTL 1시간
- `setIfAbsent`로 동일 유저의 동일 상품 재구매 원천 차단
- Kafka 발행 실패 시 `releaseUserPurchase()`로 즉시 삭제

**3단계 — Redis Lua 스크립트 (재고 선점)**
- 키: `goods:stock:{goodsId}`, TTL 없음
- Lua 스크립트로 `GET → 비교 → DECRBY`를 원자적으로 실행
  - 재고 충분: DECRBY 후 남은 재고 반환
  - 재고 부족: `-1` 반환 (DECRBY 실행 안 함)
  - 키 없음: `-2` 반환
- 음수 반환 시 Lua 내부에서 이미 차감이 발생하지 않으므로 별도 롤백 불필요
- Kafka 발행 실패 시에만 `releaseStock()`(INCRBY)으로 수동 복구

**채택하지 않은 대안:**
- DB 비관적 락: 커넥션 풀 고갈 위험
- DB 낙관적 락(`@Version`): 충돌 시 재시도 폭탄

---

## 구매 요청 흐름: Outbox 미사용 구간

**`purchase_events` 토픽은 Outbox 패턴을 사용하지 않는다.**

`PromotionService.acceptPurchase()`에서 `kafkaTemplate.send().get(3, TimeUnit.SECONDS)`로 동기 발행한다.
- 3초 타임아웃 초과 시 Redis 재고·중복구매 플래그 즉시 롤백 후 클라이언트에 에러 반환
- `stock-snapshot` (구매 접수 시점) 도 직접 발행, 실패해도 무시 (fire-and-forget)

**이유:**
- 구매 요청 단계는 아직 DB Order가 없으므로 Outbox 트랜잭션을 묶을 대상이 없음
- 대신 Redis 선점 후 Kafka 발행 실패 시 Redis 롤백으로 정합성 유지

**Outbox를 사용하는 구간 (DB 트랜잭션과 묶임):**
- `OrderCommandService`: `order-status-update`(PENDING), `payment-request`
- `SagaOrchestratorService`: `order-status-update`(PAID/FAILED/EXPIRED), `order-completed`, `payment-cancel`, `stock-snapshot`(saga 결과)

---

## Saga 패턴: Orchestration 선택

**결정:** Choreography 대신 Orchestration 채택, serverA가 단독 제어

**전체 흐름:**
```
[Client] → PromotionService → purchase_events (직접 발행, 동기 3s)
         ↓
PurchaseKafkaConsumer → OrderCommandService
  - DB: Order(PENDING) 저장, Goods 재고 차감
  - Redis: SagaState 초기화
  - Outbox: payment-request, order-status-update(PENDING)
         ↓ (병렬)
  serverC ← payment-request → payment-result → serverA/SagaResultConsumer
  serverB ← order-status-update → status-update-result → serverA/SagaResultConsumer
         ↓ (둘 다 성공 시)
SagaOrchestratorService.tryCompleteSaga()
  - DB: Order → PAID (updateStatusIfPending, 멱등성)
  - Outbox: order-status-update(PAID), order-completed
         ↓ (실패 시)
SagaOrchestratorService.handleSagaFailure()
  - DB: Order → FAILED / EXPIRED
  - Redis: 재고·유저마킹 복구
  - DB: Goods 재고 복구 (increaseStockAtomically)
  - Outbox: payment-cancel (결제가 성공했던 경우만), stock-snapshot, order-status-update(FAILED)
```

**멱등성 보장:**
- `orderRepository.updateStatusIfPending()`: PENDING 상태일 때만 변경, 중복 실행 시 0 반환
- `sagaStateService.markFailedAndCheck()`: 이미 실패 처리된 Saga는 무시

---

## Saga 타임아웃

**결정:** `SagaTimeoutScheduler`가 60초마다 Redis SCAN으로 미완료 Saga 탐지

- 키 패턴: `saga:state:*`
- 타임아웃 기준: 생성 후 **10분** 초과
- 결과: `handleSagaFailure(orderId, "TIMEOUT")` → Order 상태 **EXPIRED** (FAILED와 구분)
- 이유: 결제 응답이 없는 Saga를 무한 대기하면 Redis 상태 영구 잠김

---

## Outbox 패턴 + Debezium CDC

**결정:** Saga 내부 메시지는 `kafkaTemplate.send()` 직접 호출 대신 Outbox 테이블 → Debezium CDC

**이유:**
- `OrderCommandService`, `SagaOrchestratorService`의 메시지 발행은 DB 트랜잭션(Order 저장, 재고 차감)과 원자적으로 묶여야 함
- 직접 발행은 DB 커밋 후 Kafka 발행 실패 시 메시지 유실 위험
- Outbox 테이블 INSERT → DB 트랜잭션 성공 → Debezium CDC 감지 → Kafka 발행

**traceparent 전파:**
- `OutboxEventService.save()`가 MDC에서 현재 traceId/spanId를 읽어 W3C traceparent 형식으로 조립
- Debezium이 `outbox_event.traceparent` 컬럼 값을 Kafka 메시지 헤더로 주입
- serverC Consumer들이 헤더에서 추출해 Child Span 생성 → 단일 분산 트레이스 연결

**OutboxEventService 제약:**
- 반드시 호출자의 `@Transactional` 범위 안에서 호출해야 함 (주석으로 명시됨)
- 트랜잭션 밖에서 호출 시 메시지 유실 가능

**사용 모듈:** serverA, serverC

---

## CQRS: serverB 읽기 전용 분리

**결정:** 주문 상태·재고 조회를 serverA가 아닌 serverB(DB 없음, Redis만 사용)에서 처리

**이유:**
- 플래시세일 중 쓰기(serverA)와 읽기가 동일 서버에서 경합하면 쓰기 성능 저하
- serverB는 Redis 뷰 조회만 하므로 응답이 빠르고 serverA에 부하를 주지 않음

**트레이드오프:**
- 최종 일관성 모델 — 주문 직후 상태 조회 시 수십 밀리초 지연 가능
- 의도된 설계이며 버그가 아님

---

## DLT 처리 전략

**결정:** 재시도 소진 메시지를 DB(`dead_letter` 테이블)에 저장, 관리자 수동 재처리

**흐름:**
- `purchase_events.DLT` → `PurchaseDltConsumer` → `dead_letter` 테이블 저장
- 관리자 → `POST /api/v1/admin/dlt/{dltId}/retry` → 수동 재처리
- `payment-cancel.DLT` → 카운터 증가 + 로그 (`business_payment_pg_refund_fatal_total`)
  - 자동 재처리 없음, 수동 정산 필요

**이유:** 재시도를 모두 소진한 메시지는 시스템 오류가 아닌 데이터·비즈니스 이상일 가능성이 높아 사람이 확인 후 판단해야 함
