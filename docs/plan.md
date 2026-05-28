# 계획서: Kafka 코드 5가지 문제 수정

- 작성일: 2026-05-28

## 목표
스킬 작성 중 발견한 5가지 실제 코드 문제(불필요한 재시도, @Transactional 레이어 위반, 중복 처리 DLT 오염, groupId 불일치, span 오류 미기록)를 수정하여 운영 안정성을 높인다.

## 성공 기준
- [ ] 3개 서버 KafkaConsumerConfig에 `JsonProcessingException`이 NotRetryable 목록에 존재
- [ ] `PurchaseDltConsumer`에 `@Transactional` 없고 `DeadLetterRepository` 직접 의존 없음
- [ ] 중복 orderId 메시지가 들어올 경우 DLT 행이 아닌 warn 로그 후 정상 종료
- [ ] `PaymentCancelConsumer` DLT groupId가 `${spring.kafka.consumer.group-id}.dlt` 형태
- [ ] `PaymentKafkaConsumer`, `PaymentCancelConsumer`, `OrderCompletedConsumer`의 span catch 블록에 `span.error(e)` 존재
- [ ] 변경된 클래스의 테스트가 통과 (`gradlew test` 사용자 실행)

## 비범위 (Out of Scope)
- `throws Exception` → `throws JsonProcessingException` 시그니처 변경
- ServerB `OrderStatusEventHandler`의 fire-and-forget 개선
- `SagaStateService.initSagaState` 중복 호출 방어
- DLT에 raw payload 보존 컬럼 추가

## 단계별 작업 계획

### 단계 1: JsonProcessingException NotRetryable 추가
- 변경 파일: `serverA/config/KafkaConsumerConfig.java`, `serverB/config/KafkaConsumerConfig.java`, `serverC/config/KafkaConsumerConfig.java`
- 변경 내용: 각 `errorHandler()` 빈의 `addNotRetryableExceptions()`에 `JsonProcessingException.class` 추가 + import 추가
- 검증 방법: 3파일 grep으로 `JsonProcessingException` 존재 확인
- 롤백 방법: 해당 라인 삭제
- 예상 소요: 짧음

### 단계 2: PurchaseDltConsumer 리팩토링
- 변경 파일: `serverA/kafka/PurchaseDltConsumer.java`, `serverA/test/.../PurchaseDltConsumerTest.java`
- 변경 내용: `@Transactional` 제거, `DeadLetterRepository` 의존 제거, 기존 `DeadLetterService` 주입으로 교체. `deadLetterService.saveDeadLetter(orderId, goodsId, quantity, reason)` 호출로 변경. 테스트는 `@Mock DeadLetterService`로 교체.
- 검증 방법: `PurchaseDltConsumerTest` 통과
- 롤백 방법: git restore
- 예상 소요: 짧음

### 단계 3: OrderCommandService 멱등 처리 명시화
- 변경 파일: `serverA/service/OrderCommandService.java`, `serverA/test/.../OrderCommandServiceTest.java`
- 변경 내용: `saveOrderAndDecreaseStock()` 첫 줄에 `findByOrderId()` 체크 추가. 중복이면 warn 로그 후 기존 Order 반환. 테스트에 중복 메시지 케이스 추가.
- 검증 방법: `OrderCommandServiceTest` 통과 (기존 테스트 + 신규 중복 케이스)
- 롤백 방법: git restore
- 예상 소요: 짧음

### 단계 4: PaymentCancelConsumer DLT groupId + span.error 수정
- 변경 파일: `serverC/kafka/PaymentCancelConsumer.java`
- 변경 내용: DLT groupId를 `${spring.kafka.consumer.group-id}.dlt`로 변경. span try 블록에 catch + `span.error(e)` + rethrow 추가.
- 검증 방법: 파일 내용 확인
- 롤백 방법: git restore
- 예상 소요: 짧음

### 단계 5: PaymentKafkaConsumer, OrderCompletedConsumer span.error 추가
- 변경 파일: `serverC/kafka/PaymentKafkaConsumer.java`, `serverC/kafka/OrderCompletedConsumer.java`
- 변경 내용: span try 블록에 catch + `span.error(e)` + rethrow 패턴 추가
- 검증 방법: 파일 내용 확인
- 롤백 방법: git restore
- 예상 소요: 짧음

## 리스크 및 대응
- `DeadLetterService.saveDeadLetter`는 `Propagation.REQUIRES_NEW` → Consumer 컨텍스트에서 호출해도 독립 트랜잭션 보장, 문제 없음
- 단계 3의 `findByOrderId` 추가로 정상 경로에 SELECT 1회 추가됨 → `idx_orders_order_id` 인덱스 존재하여 성능 영향 무시 가능

## 의존성
- `DeadLetterService`가 `serverA/service/dlt/`에 이미 존재 — 신규 생성 불필요
- `OrderRepository.findByOrderId()` 이미 존재 — Repository 변경 불필요
