# 계획서: 트랜잭셔널 아웃박스 패턴 도입 (DB Polling 방식)

- 작성일: 2026-05-24
- 관련 이슈: ServerA/C Kafka 전송 실패 시 데이터 정합성 파괴 문제

## 목표

ServerA와 ServerC의 Kafka 발행을 DB 트랜잭션과 원자적으로 묶어, Kafka 브로커 장애 시에도
메시지 유실 없이 at-least-once 발행을 보장한다. 폴링 방식으로 구현하여 추후 CDC(Debezium 등)로
Relay 교체가 용이하도록 설계한다.

## 성공 기준

- [ ] `outbox_event` 테이블에 비즈니스 DB 변경과 동일 트랜잭션으로 이벤트가 저장된다 (코드 확인)
- [ ] `KafkaProduceService` 삭제 후 ServerA 빌드 통과 (`.\gradlew.bat :serverA:build`)
- [ ] ServerC 빌드 통과 (`.\gradlew.bat :serverC:build`)
- [ ] 2초마다 PENDING 이벤트가 Kafka에 발행되고 SENT로 상태 변경된다 (로그 확인)
- [ ] 24시간 초과 SENT 이벤트가 주기적으로 삭제된다 (코드 확인)
- [ ] SELECT FOR UPDATE SKIP LOCKED로 다중 인스턴스 중복 발행 방지 (코드 확인)

## 비범위 (Out of Scope)

- ServerB: MySQL 없음, 기존 SagaTimeout(10분) fallback으로 유지
- CDC(Debezium) 전환: 추후 Relay 스케줄러만 제거하면 전환 가능하도록 설계
- FAILED 상태 별도 관리: retry는 PENDING 재처리로 충분, 별도 FAILED 상태 없음
- outbox_event 테이블 파티셔닝: 현재 규모 불필요

## 단계별 작업 계획

### 단계 1: ServerA Outbox 인프라 + Relay 스케줄러 신규 파일 작성

- 변경 파일 (신규):
  - `serverA/.../outbox/OutboxStatus.java` — PENDING/SENT enum
  - `serverA/.../outbox/OutboxEvent.java` — outbox_event 테이블 엔티티
  - `serverA/.../outbox/OutboxEventRepository.java` — SKIP LOCKED 네이티브 쿼리 포함
  - `serverA/.../outbox/OutboxEventService.java` — 트랜잭션 내 저장 메서드
  - `serverA/.../outbox/OutboxRelayScheduler.java` — 2초 폴링 + 1시간 cleanup
- 변경 파일 (수정):
  - `serverA/.../config/SchedulingConfig.java` — pool size 3 -> 4
- 검증 방법: `.\gradlew.bat :serverA:compileJava`
- 롤백 방법: 신규 파일 5개 삭제, SchedulingConfig 원복
- 예상 소요: 보통

### 단계 2: ServerA 기존 코드 Outbox로 전환

- 변경 파일 (수정):
  - `OrderCommandService.java` — OutboxEventService 주입, tx 내 2개 이벤트 저장
    (order-status-update PENDING, payment-request)
  - `SagaOrchestratorService.java` — KafkaTemplate 제거, OutboxEventService로 전환
    (order-status-update PAID/FAILED/EXPIRED, order-completed, stock-snapshot)
  - `PurchaseKafkaConsumer.java` — KafkaProduceService 의존성 제거
- 변경 파일 (삭제):
  - `kafka/KafkaProduceService.java`
- 변경 파일 (수정, 테스트):
  - `KafkaProduceServiceTest.java` — 삭제
  - `PurchaseKafkaConsumerTest.java` — KafkaProduceService mock 제거
  - `SagaOrchestratorServiceTest.java` — KafkaTemplate mock -> OutboxEventService mock
  - `OrderCommandServiceTest.java` — OutboxEventService mock 추가
- 검증 방법: `.\gradlew.bat :serverA:build`
- 롤백 방법: git으로 단계 1 이전 상태로 되돌림
- 예상 소요: 보통

### 단계 3: ServerC Outbox 인프라 + Relay 스케줄러 신규 파일 작성

- 변경 파일 (신규):
  - `serverC/.../outbox/OutboxStatus.java`
  - `serverC/.../outbox/OutboxEvent.java`
  - `serverC/.../outbox/OutboxEventRepository.java`
  - `serverC/.../outbox/OutboxEventService.java`
  - `serverC/.../outbox/OutboxRelayScheduler.java`
  - `serverC/.../config/SchedulingConfig.java` — @EnableScheduling 활성화 (기존 없음)
- 검증 방법: `.\gradlew.bat :serverC:compileJava`
- 롤백 방법: 신규 파일 6개 삭제
- 예상 소요: 짧음 (ServerA와 동일 패턴)

### 단계 4: ServerC PaymentService Outbox로 전환

- 변경 파일 (수정):
  - `PaymentService.java` — @Transactional 추가, KafkaTemplate 제거,
    OutboxEventService 주입, payment-result 이벤트 저장
  - `PaymentServiceTest.java` — KafkaTemplate mock -> OutboxEventService mock
- 검증 방법: `.\gradlew.bat :serverC:build`
- 롤백 방법: PaymentService 원복
- 예상 소요: 짧음

### 단계 5: 전체 빌드 최종 검증

- 검증 방법: `.\gradlew.bat build`
- 롤백 방법: 해당 없음 (검증 단계)
- 예상 소요: 짧음

## 리스크 및 대응

- **Relay 중 Kafka 장애**: Kafka send 실패 시 예외 로깅 후 SENT 미갱신 -> 다음 폴링에서 재처리.
  at-least-once이므로 소비자 측 멱등성(traceId unique 제약 등)이 보장해야 함.
- **JpaTransactionManager와 JdbcTemplate 공존 (ServerC)**: JpaTransactionManager가 DataSource를 관리하므로
  @Transactional 범위 내 JdbcTemplate 호출도 동일 트랜잭션에 참여. 문제 없음.
- **outbox_event 테이블 DDL**: ddl-auto=create/update면 자동 생성. validate/none이면 수동 DDL 필요.

## 의존성

- MySQL `FOR UPDATE SKIP LOCKED` 지원: MySQL 8.0+
- KafkaTemplate 빈: ServerA/C 모두 이미 존재
- ObjectMapper 빈: ServerA/C 모두 이미 존재
- JPA 의존성: ServerA/C 모두 이미 존재
