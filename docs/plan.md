# 계획서: Saga 상태 복구 안전망 (타임아웃 전 결제조회 보정 + DB 기반 PENDING 재대사)

- 작성일: 2026-11-12
- 관련 이슈/티켓: 없음 (인터뷰 준비 중 발견한 설계 공백 보완)

## 목표
1. `SagaTimeoutScheduler`가 3분 타임아웃으로 `EXPIRED` 처리하기 전에, serverC에 결제 완료 여부를 실제로 조회해 이미 결제된 정상 주문까지 취소되는 문제를 막는다.
2. Redis Saga 상태(`saga:state:*`)가 유실되어 `SagaTimeoutScheduler`가 아예 그 주문을 못 찾는 경우를 대비해, DB `orders` 테이블을 직접 스캔하는 별도 재대사 스케줄러를 추가한다.

## 성공 기준
- [ ] `PaymentQueryClient`가 serverC `GET /api/v1/payments/{orderId}`를 호출해 PAID/NOT_PAID/UNKNOWN을 정확히 구분한다 (단위 테스트)
- [ ] `SagaTimeoutScheduler`가 타임아웃 시 결제 완료 확인되면 `EXPIRED` 대신 `PAID`로 보정한다 (단위 테스트)
- [ ] 신규 `OrderReconciliationScheduler`가 5분 이상 `PENDING`인 주문을 DB에서 직접 찾아 결제 상태에 따라 `PAID`/`FAILED`로 보정하고, `FAILED`인 경우 재고를 복구한다 (단위 테스트)
- [ ] `./gradlew.bat :serverA:test` 전체 통과
- [ ] `git diff --stat`으로 변경 범위가 serverA 내부로 한정됨을 확인

## 비범위 (Out of Scope)
- Prometheus 알람/메트릭 추가 (사용자 요청에 따라 제외)
- `order-completed` 이벤트 발행 (보정 경로에서는 생략 — 해당 컨슈머는 로그만 남김)
- 기존 `handleSagaFailure`의 "조건부 UPDATE 반환값 무시" 같은 기존 로직 수정 (발견했지만 손대지 않음, 완료 보고에 명시)
- 실제 PG/VAN 연동, 정산 배치 등 — 여전히 범위 밖

## 단계별 작업 계획

### 단계 1: PaymentQueryClient + 관련 DTO/Enum + RestClient Bean 추가
- 변경 파일:
  - `serverA/src/main/java/promotion/serverA/config/RestClientConfig.java` (신규)
  - `serverA/src/main/java/promotion/serverA/client/PaymentQueryClient.java` (신규)
  - `serverA/src/main/java/promotion/serverA/client/PaymentCheckStatus.java` (신규)
  - `serverA/src/main/java/promotion/serverA/client/dto/PaymentStatusResponse.java` (신규)
- 변경 내용 요약: cs-bot의 `CsBotClient`/`RestClientConfig` 패턴을 그대로 따라, `DiscoveryClient`로 serverC 인스턴스를 찾고 `GET /api/v1/payments/{orderId}`를 호출한다. 응답 `status`가 `"PAID"`면 `PAID`, 404(`PaymentNotFoundException`)나 다른 상태 문자열이면 `NOT_PAID`, 그 외 예외(서비스 다운/타임아웃 등)는 `UNKNOWN`으로 분류한다.
- 검증 방법: `PaymentQueryClientTest` — Mockito로 `DiscoveryClient`/`RestClient.Builder`를 모킹해 PAID/404/기타예외 3가지 분기 검증
- 롤백 방법: 신규 파일 4개 삭제
- 예상 소요: 보통

### 단계 2: SagaOrchestratorService에 재대사 메서드 추가
- 변경 파일: `serverA/src/main/java/promotion/serverA/service/SagaOrchestratorService.java`
- 변경 내용 요약: `reconcileToPaid(String orderId)`와 `reconcileToFailed(String orderId)` 추가. 둘 다 `orderRepository.findByOrderId`로 Order를 직접 조회해 필요한 데이터(goodsId/quantity/userId)를 얻는다 — Redis `SagaStateData`에 의존하지 않음. `reconcileToFailed`는 기존 `handleSagaFailure`의 보상 로직(Redis 재고 복구, DB 재고 복구, Outbox stock-snapshot/order-status-update)과 동일한 패턴을 Order 엔티티 기준으로 재현한다. `updateStatusIfPending`의 반환값(갱신 row 수)을 확인해 0이면 보상을 건너뛴다 — 기존 `handleSagaFailure`는 이 반환값을 확인하지 않는 기존 갭이 있으나 이번 범위에서는 손대지 않는다.
- 검증 방법: `SagaOrchestratorServiceTest`에 `reconcileToPaid_Success`, `reconcileToFailed_Success`, `reconcileToFailed_AlreadyProcessed_SkipsCompensation` 등 추가
- 롤백 방법: 추가한 메서드와 테스트만 제거
- 예상 소요: 보통

### 단계 3: SagaTimeoutScheduler에 결제조회 보정 분기 추가
- 변경 파일:
  - `serverA/src/main/java/promotion/serverA/scheduler/SagaTimeoutScheduler.java`
  - `serverA/src/test/java/promotion/serverA/scheduler/SagaTimeoutSchedulerTest.java`
- 변경 내용 요약: 타임아웃 탐지 시 `paymentQueryClient.checkPaymentStatus(orderId)`를 호출. `PAID`면 `sagaOrchestratorService.reconcileToPaid(orderId)` 호출(EXPIRED 카운터 증가 없음). `NOT_PAID`/`UNKNOWN`이면 기존과 동일하게 `handleSagaFailure(orderId, "TIMEOUT")` + `saga.expired.total` 증가(기존 동작 그대로 유지, 조회 실패 시 안전하게 기존 동작으로 폴백).
- 검증 방법: 신규 테스트 케이스 — PAID 조회 시 `reconcileToPaid` 호출 및 `handleSagaFailure` 미호출 검증, 기존 3개 테스트는 `paymentQueryClient` 모킹 추가 후 통과 유지
- 롤백 방법: 분기 추가 전 코드로 되돌림
- 예상 소요: 보통

### 단계 4: OrderRepository에 PENDING 방치 주문 조회 쿼리 추가
- 변경 파일: `serverA/src/main/java/promotion/serverA/repository/OrderRepository.java`
- 변경 내용 요약: `List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime createdAt)` 추가. 기존 `idx_orders_status_created` 인덱스(`status, created_at`)를 그대로 활용 — 추가 인덱스/마이그레이션 불필요.
- 검증 방법: Testcontainers 기반 `OrderRepositoryTest`가 존재하면 테스트 추가 후 실행(Docker 가동 중이면 Claude가 직접 실행, 아니면 사용자에게 요청). 없으면 메서드 시그니처만 추가하고 단계 5의 스케줄러 테스트에서 간접 검증.
- 롤백 방법: 메서드 제거
- 예상 소요: 짧음

### 단계 5: OrderReconciliationScheduler 신규 추가
- 변경 파일:
  - `serverA/src/main/java/promotion/serverA/scheduler/OrderReconciliationScheduler.java` (신규)
  - `serverA/src/test/java/promotion/serverA/scheduler/OrderReconciliationSchedulerTest.java` (신규)
- 변경 내용 요약: `@Scheduled(fixedDelay = 2 * 60_000)`로 2분마다 `orderRepository.findByStatusAndCreatedAtBefore(PENDING, now.minusMinutes(5))`를 조회. 각 주문에 대해 `paymentQueryClient.checkPaymentStatus`로 PAID/NOT_PAID/UNKNOWN 분기 → `reconcileToPaid`/`reconcileToFailed`/보류(로그만, 다음 주기 재시도).
- 검증 방법: Mockito 기반 단위 테스트 — PAID/NOT_PAID/UNKNOWN 3가지 분기 각각 올바른 메서드 호출 검증
- 롤백 방법: 신규 파일 2개 삭제
- 예상 소요: 보통

## 리스크 및 대응
- 리스크 1: `reconcileToFailed`가 `handleSagaFailure`와 보상 로직이 유사해 중복처럼 보일 수 있음 → 대응: Redis `SagaStateData` 의존 여부가 본질적으로 다르므로(이 기능의 핵심 목적) 통합하지 않고 별도 유지, context.md에 이유 명시
- 리스크 2: 두 스케줄러가 동시에 같은 주문을 건드릴 race → 대응: 둘 다 `updateStatusIfPending`(조건부 UPDATE) 기반이라 한쪽이 먼저 처리하면 다른 쪽은 0 row 갱신으로 자동 스킵됨(이미 멱등하게 설계됨)

## 의존성
- serverC `GET /api/v1/payments/{orderId}` 엔드포인트 (이미 존재, 변경 없음)
- serverA는 이미 `spring-cloud-starter-netflix-eureka-client` + `spring-boot-starter-web` 의존성을 가지고 있어 새 Gradle 의존성 추가 없음
