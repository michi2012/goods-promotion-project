# 맥락 노트: 트랜잭셔널 아웃박스 패턴 도입 (DB Polling 방식)

## 왜 이 방식을 선택했는가

현재 시스템의 Kafka 발행은 DB commit 이후 별도 네트워크 호출로 이루어져 있어,
Kafka 브로커 장애 시 "DB는 변경됐지만 메시지는 유실"되는 정합성 파괴가 발생한다.

특히 두 가지 시나리오가 치명적이다:

1. **ServerC PaymentService**: PG 결제 완료 + DB PAID 기록 후 payment-result 발행 실패
   -> ServerA SagaTimeout이 보상 트랜잭션 실행 -> 고객은 결제됐는데 주문은 취소됨

2. **ServerA tryCompleteSaga**: Order PAID 기록 후 order-status-update/order-completed 발행 실패
   -> SagaState가 삭제되어 타임아웃 스케줄러도 감지 불가 -> ServerB/C 영원히 미통보

아웃박스 패턴은 비즈니스 상태 변경과 "발행할 메시지"를 동일 트랜잭션으로 묶어
둘 다 commit되거나 둘 다 rollback됨을 보장한다. Relay(폴링 스케줄러)가 PENDING
이벤트를 주기적으로 꺼내 Kafka에 발행하고 SENT로 마킹한다.

폴링 방식을 선택한 이유: 추후 CDC(Debezium)로 교체 예정. Relay 스케줄러만 제거하면
교체 가능하도록, outbox_event 테이블 구조를 CDC가 읽기 좋은 형태로 유지한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: send().get() 동기화만 적용
- 무엇: kafkaTemplate.send().get(5, SECONDS)로 동기 대기, 실패 시 예외 전파 -> Consumer가 offset
  미커밋 -> Kafka 재처리
- 왜 안 썼나: DB commit 후 Kafka 전송이 실패하면 여전히 불일치. DB는 롤백 불가. ServerC의
  결제 처리 후 발행 실패 시나리오를 근본적으로 해결하지 못함.

### 대안 B: Kafka Transactions (KafkaTransactionManager)
- 무엇: Kafka 자체 트랜잭션으로 produce의 원자성 보장
- 왜 안 썼나: DB 트랜잭션과 Kafka 트랜잭션의 원자성은 보장하지 못함 (2PC 없음). 즉,
  DB commit 성공 + Kafka commit 실패 시나리오는 여전히 발생. 문제를 해결하지 않음.

### 대안 C: Kafka Streams + CQRS 완전 분리
- 무엇: 이벤트 소싱으로 완전히 재설계
- 왜 안 썼나: 기존 Saga 아키텍처 전면 재설계가 필요. 범위 초과.

## 아웃박스 이벤트 흐름

```
[ServerA Phase 1]
OrderCommandService.saveOrderAndDecreaseStock() @Transactional
  -> Order 저장 + 재고 감소 + SagaState Redis 저장
  -> outbox 저장: order-status-update(PENDING), payment-request   <- 동일 TX
  (PurchaseKafkaConsumer에서 KafkaProduceService 호출 제거)

[ServerA Phase 2 - 성공]
SagaOrchestratorService.tryCompleteSaga() @Transactional
  -> Order PENDING -> PAID
  -> outbox 저장: order-status-update(PAID), order-completed       <- 동일 TX
  -> sagaStateService.deleteSagaState()

[ServerA Phase 2 - 실패/보상]
SagaOrchestratorService.handleSagaFailure() @Transactional
  -> Order -> FAILED/EXPIRED + Redis 복구 + DB 재고 복구
  -> outbox 저장: order-status-update(FAILED/EXPIRED), stock-snapshot <- 동일 TX

[ServerC 결제]
PaymentService.processPayment() @Transactional (신규 추가)
  -> final_order PROCESSING -> PAID/FAILED
  -> outbox 저장: payment-result                                    <- 동일 TX

[Relay - 2초마다]
OutboxRelayScheduler.relay() @Transactional
  -> SELECT * FROM outbox_event WHERE status='PENDING' FOR UPDATE SKIP LOCKED LIMIT 50
  -> kafkaTemplate.send(...).get(5, SECONDS)
  -> 성공: status = SENT, sent_at = now()
  -> 실패: 예외 로깅, PENDING 유지 (다음 폴링 재처리)

[Cleanup - 1시간마다]
OutboxRelayScheduler.cleanup()
  -> DELETE FROM outbox_event WHERE status='SENT' AND sent_at < NOW() - 24h
```

## ServerB 제외 이유

ServerB는 Redis만 사용 (MySQL 없음). outbox_event 테이블을 만들려면 MySQL 추가가 필요하고
작업 범위가 크게 늘어난다. ServerB의 status-update-result 발행 실패는 기존
SagaTimeout(10분)이 ServerA측에서 보상 처리하므로 허용 가능한 수준이다.

## 기존 코드베이스 컨벤션

- 패키지 구조: 기능별 (kafka/, service/, entity/, config/, outbox/ 신규)
- 엔티티: @Getter + 도메인 메서드, @Data/@Setter 금지, BaseTimeEntity 상속 패턴 있음
- Repository: JpaRepository 기반, 복잡 쿼리는 @Query
- 스케줄러: SchedulingConfig에서 ThreadPoolTaskScheduler 통합 관리
- 테스트: Mockito 기반 단위 테스트, @ExtendWith(MockitoExtension.class)

## 관련 파일/위치

- `serverA/.../service/OrderCommandService.java` — Phase 1 DB 처리, outbox 저장 추가 위치
- `serverA/.../service/SagaOrchestratorService.java` — Phase 2 성공/실패 처리, KafkaTemplate 제거 위치
- `serverA/.../kafka/PurchaseKafkaConsumer.java` — KafkaProduceService 제거 위치
- `serverA/.../kafka/KafkaProduceService.java` — 삭제 대상
- `serverC/.../service/PaymentService.java` — 결제 처리 + outbox 저장, @Transactional 추가 위치
- `serverA/.../config/SchedulingConfig.java` — pool size 조정
