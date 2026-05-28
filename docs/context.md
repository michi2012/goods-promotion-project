# 맥락 노트: Kafka 코드 5가지 문제 수정

## 왜 이 방식을 선택했는가

### 문제 1 — JsonProcessingException NotRetryable
`objectMapper.readValue()` 실패는 메시지 자체가 잘못된 것이라 재시도해도 항상 실패한다.
기존 `MessageConversionException`만 등록되어 있어 `JsonProcessingException`이 누락된 상태였다.
3번 불필요한 재시도 후 DLT로 가는 구조를 즉시 DLT행으로 단락.

### 문제 2 — PurchaseDltConsumer @Transactional 제거
`@KafkaListener` 메서드에 `@Transactional`을 직접 붙이는 건 레이어 위반.
`DeadLetterService`가 이미 `REQUIRES_NEW` 전파로 독립 트랜잭션을 보장하므로,
Consumer는 역직렬화 + Service 호출만 하도록 단순화.

### 문제 3 — OrderCommandService 멱등 처리 명시화
`Order.orderId`에 `unique = true`가 있어 중복 시 `DataIntegrityViolationException`이 발생하고
NotRetryable 분류로 DLT에 쌓이는 구조였다. 이는 실제 에러가 아님에도 DLT 모니터링을 오염시킨다.
`findByOrderId()`로 먼저 체크해 중복을 정상 경로로 처리하도록 변경.

### 문제 4 — DLT groupId 명명 통일
`PurchaseDltConsumer`는 `.dlt`, `PaymentCancelConsumer`는 `-payment-cancel-dlt`로 불일치.
`.dlt` suffix로 통일. 컨슈머 그룹 prefix 자체는 서버별로 다르므로 충돌 없음.

### 문제 5 — span.error(e) 누락
예외 발생 시 `span.error(e)` 없으면 Tempo에서 해당 span이 정상(OK)으로 표시된다.
`PaymentKafkaConsumer`, `PaymentCancelConsumer`, `OrderCompletedConsumer` 세 곳 모두 추가.

## 검토했으나 채택하지 않은 대안

### 문제 3 대안 — existsByOrderId 별도 메서드
- 무엇: Repository에 `existsByOrderId(String)` 추가 후 체크
- 왜 안 썼나: `findByOrderId()`가 이미 존재하고, 중복 케이스에서는 어차피 기존 Order를 반환해야 하므로 한 번 조회로 충분하다.

### 문제 3 대안 — DataIntegrityViolationException catch 후 skip
- 무엇: 서비스 내에서 예외를 catch해서 중복으로 처리
- 왜 안 썼나: 예외 기반 흐름 제어는 가독성이 낮고, 실제 중복 키 위반과 다른 DataIntegrity 에러를 구분할 수 없다.

## 기존 코드베이스 컨벤션
- Consumer 패턴: `kafka/` 패키지, `@Component`, `@KafkaListener`
- DLT Consumer: `dltKafkaListenerContainerFactory`, groupId suffix `.dlt`
- Trace Consumer: `ConsumerRecord` 파라미터, `extractTraceparent` + `buildChildSpan` 헬퍼
- Service 트랜잭션: `@Transactional(readOnly = true)` 클래스 레벨, 변경 메서드만 `@Transactional`
- DeadLetterService: `serverA/service/dlt/DeadLetterService.java`, `REQUIRES_NEW` 전파

## 관련 파일/위치
- `serverA/config/KafkaConsumerConfig.java` — 문제 1
- `serverB/config/KafkaConsumerConfig.java` — 문제 1
- `serverC/config/KafkaConsumerConfig.java` — 문제 1
- `serverA/kafka/PurchaseDltConsumer.java` — 문제 2
- `serverA/service/dlt/DeadLetterService.java` — 문제 2 (기존, 수정 없음)
- `serverA/service/OrderCommandService.java` — 문제 3
- `serverC/kafka/PaymentCancelConsumer.java` — 문제 4 + 5
- `serverC/kafka/PaymentKafkaConsumer.java` — 문제 5
- `serverC/kafka/OrderCompletedConsumer.java` — 문제 5
