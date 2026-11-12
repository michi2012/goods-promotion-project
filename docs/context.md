# 맥락 노트: Saga 상태 복구 안전망

## 왜 이 방식을 선택했는가
인터뷰 준비 중 두 가지 설계 공백이 드러났다:
1. `SagaTimeoutScheduler`(`serverA/.../scheduler/SagaTimeoutScheduler.java`)는 3분 타임아웃이면 결제 완료 여부를 확인하지 않고 무조건 `EXPIRED` 처리한다. 결제가 실제로는 승인됐는데 후속 단계가 느려서 타임아웃된 경우, 고객 결제를 취소(망취소)해버리는 문제가 있다.
2. 이 스케줄러는 Redis `saga:state:*` 키 존재를 전제로 스캔한다. Redis 상태가 유실되면(재시작/eviction) 그 주문은 영원히 `PENDING`으로 DB에 남고, 서버B에도 상태가 재동기화되지 않는다.

두 문제 모두 "결제 서버(serverC)가 유일한 진실 소스"라는 공통 해법으로 풀린다: Redis/Saga 상태를 믿지 못할 때는 serverC에 직접 물어본다.

## 검토했으나 채택하지 않은 대안

### 대안 A: handleSagaFailure를 확장해서 결제조회 분기까지 넣기
- 무엇: 기존 `handleSagaFailure(orderId, reason)` 내부에서 결제조회 후 PAID면 상태를 바꿔서 리턴
- 왜 안 썼나: `handleSagaFailure`는 Redis `SagaStateData`에 강하게 의존한다(goodsId/quantity를 Redis에서 가져옴). DB 기반 재대사 스케줄러는 Redis 상태가 없다는 전제이므로 이 메서드를 그대로 재사용할 수 없다. 대신 Order 엔티티에서 직접 데이터를 읽는 별도 메서드(`reconcileToPaid`/`reconcileToFailed`)로 분리했다.

### 대안 B: Order 엔티티를 SagaStateData 대신 쓰도록 handleSagaFailure 자체를 리팩터링
- 무엇: `SagaStateData` 대신 처음부터 Order 엔티티를 쓰게 통합
- 왜 안 썼나: 기존에 잘 동작하는 Redis 기반 타임아웃 경로를 건드리는 건 이번 요청 범위를 넘어선다(Surgical Changes 원칙). 새 기능만 추가하고 기존 경로는 그대로 둔다.

### 대안 C: 재대사 스케줄러의 PENDING 방치 기준을 10분으로
- 무엇: Saga 타임아웃(3분) + 스케줄러 주기를 넉넉히 넘기는 10분을 기준으로 제안
- 왜 안 썼나: 사용자가 5분을 선택함 — 더 빠른 탐지를 선호. 정상 흐름이 5분 내로 끝나지 않는 주문을 오탐할 가능성은 낮다고 판단(실제 Saga는 3분 타임아웃으로 종료됨).

## 기존 코드베이스 컨벤션
- 서비스 간 동기 호출: `DiscoveryClient` + `RestClient`, `Qualifier("internalServiceClientBuilder")` 빈 — `cs-bot/.../client/CsBotClient.java`, `cs-bot/.../config/RestClientConfig.java` 패턴을 serverA에 그대로 복제
- 멱등 보정: `OrderRepository.updateStatusIfPending` 조건부 UPDATE — 이미 존재하는 패턴을 그대로 재사용
- Outbox를 통한 이벤트 발행: `OutboxEventService.save(aggregateId, topic, payload)` — 트랜잭션 내에서 호출
- 애매한 응답(조회 실패) 처리: serverC의 `PgTransactionStatus{APPROVED,REJECTED,NOT_FOUND}` 패턴과 동일한 사상으로 `PaymentCheckStatus{PAID,NOT_PAID,UNKNOWN}`를 설계 — "확정된 실패"와 "조회 자체가 안 됨"을 구분해서 안전하게 처리

## 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/scheduler/SagaTimeoutScheduler.java` — 타임아웃 스캔, 이번에 결제조회 분기 추가
- `serverA/src/main/java/promotion/serverA/service/SagaOrchestratorService.java` — 보상/보정 로직 중심. `reconcileToPaid`/`reconcileToFailed` 추가
- `serverA/src/main/java/promotion/serverA/repository/OrderRepository.java` — `findByStatusAndCreatedAtBefore` 추가, 기존 `idx_orders_status_created` 인덱스 활용
- `serverA/src/main/java/promotion/serverA/entity/Order.java` — `goodsId`/`quantity`/`userId` 컬럼을 이미 가지고 있어 Redis 상태 없이도 보상 로직에 필요한 데이터를 얻을 수 있음 (이번 설계의 핵심 전제)
- `serverC/src/main/java/promotion/serverC/controller/PaymentController.java` — `GET /api/v1/payments/{orderId}`, 변경 없이 그대로 소비
- `cs-bot/src/main/java/csbot/csbot/client/CsBotClient.java`, `cs-bot/src/main/java/csbot/csbot/config/RestClientConfig.java` — 복제할 패턴의 원본

## 발견했지만 손대지 않은 것
- `handleSagaFailure`가 `orderRepository.updateStatusIfPending`의 반환값(갱신 row 수)을 확인하지 않고 항상 보상(재고 복구 등)을 진행한다 — 이미 다른 경로로 PAID 처리된 주문에 대해서도 보상이 실행될 잠재적 갭. 이번 범위에서는 기존 코드를 수정하지 않고, 신규 메서드(`reconcileToPaid`/`reconcileToFailed`)에서만 반환값을 확인하도록 했다.

## 외부 참조
없음
