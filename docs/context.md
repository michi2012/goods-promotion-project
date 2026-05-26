# 맥락 노트: 결제 메트릭 에러 타입 레이블 분리

## 왜 이 방식을 선택했는가

기존 `business_payment_error_total` 단일 카운터는 인프라 장애(서킷브레이커 개방, PG 타임아웃)와 비즈니스 거절(잔액부족, 카드한도)을 구분하지 않아 알람이 오발화될 수 있었다.

fallback이 정상 반환(`List<String>`)을 하면 `PaymentService`에서 두 경로를 구분할 방법이 없으므로, fallback이 `PgPaymentException`을 throw해 명시적인 시그널을 전달한다. `PaymentService`는 이를 catch해 `pg_system_error`로 분류하고, **예외를 re-throw하지 않아 `@Transactional` 트랜잭션이 FAILED 상태와 Outbox 이벤트를 정상 커밋**하도록 한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: MockPgClient에서 직접 메트릭 기록
- 무엇: `fallbackProcessPayments` 내부에서 `meterRegistry.counter(..., "pg_system_error").increment()`를 호출하고 정상 반환
- 왜 안 썼나: `PaymentService`의 `paymentError.increment()`와 이중 카운트 발생. 또한 비즈니스 로직(메트릭 분류)이 인프라 레이어(PgClient)에 누수됨.

### 대안 B: 반환값에 타입 정보 포함 (래퍼 객체)
- 무엇: `List<String>` 대신 `PaymentResult(failedIds, errorType)` 반환
- 왜 안 썼나: `PgClient` 인터페이스 시그니처 변경이 필요하고, Resilience4j fallback 시그니처도 맞춰야 해 변경 범위가 과도하게 커짐.

### 대안 C: re-throw 후 Kafka 재시도 허용
- 무엇: catch 블록에서 예외를 re-throw해 Kafka가 3회 재시도하도록 함
- 왜 안 썼나: 서킷브레이커가 OPEN 상태인 한 재시도는 모두 즉시 fallback → 재시도 의미 없음. 또한 re-throw 시 `@Transactional` 롤백으로 FAILED 상태와 Outbox 이벤트가 저장되지 않아 SAGA 보상 트랜잭션이 트리거되지 않음.

## 관련 파일/위치
- `serverC/src/main/java/weverse/serverC/service/PaymentService.java` — 결제 처리 및 메트릭 기록 주체
- `serverC/src/main/java/weverse/serverC/service/MockPgClient.java` — 서킷브레이커 + PG 시뮬레이션, fallback 정의
- `serverC/src/main/java/weverse/serverC/exception/PgPaymentException.java` — pg_system_error 시그널 예외
- `monitoring/prometheus/alert-rules.yml` — Tier4 PaymentBusiness 룰셋
