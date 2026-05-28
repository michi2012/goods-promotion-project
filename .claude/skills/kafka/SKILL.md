---
name: kafka
description: Kafka 컨슈머/프로듀서 구현, DLT 설계, 에러 핸들러 설정, 토픽 설계, 멱등 소비자, Debezium 아웃박스 패턴 작업 시 활성화. 사용자가 Kafka, KafkaListener, KafkaTemplate, DLT, Dead Letter, 컨슈머, 프로듀서, 토픽, Debezium, CDC, 아웃박스 를 언급하거나 kafka/ 폴더 작업 시 발동.
---

# Kafka / Spring Kafka 규칙

## 0. 작업 시작 전

새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 해당 서버의 기존 `KafkaConsumerConfig.java` (에러 핸들러 설정)
- 해당 서버의 기존 `KafkaTopicConfig.java` (토픽 명명 패턴)
- 유사한 기존 Consumer 1개 (`kafka/` 폴더)

기존 패턴을 **그대로 따른다**. 새 패턴을 도입하지 마라.

---

## 1. 이 프로젝트의 Kafka 구조

```
ServerA (KafkaTemplate 동기 발행)
  └─ purchase_events ──────────────────────────────▶ ServerA Consumer (자신 소비)
  └─ payment-request ──────────────────────────────▶ ServerC PaymentKafkaConsumer
  └─ order-status-update ──────────────────────────▶ ServerB OrderStatusKafkaConsumer
  └─ payment-cancel ───────────────────────────────▶ ServerC PaymentCancelConsumer

ServerB (KafkaTemplate fire-and-forget 발행)
  └─ status-update-result ─────────────────────────▶ ServerA SagaResultConsumer

ServerC (KafkaTemplate 발행 없음, Debezium CDC 활용)
  └─ payment-result (Debezium outbox) ─────────────▶ ServerA

모든 토픽에 .DLT 대응 토픽 존재 (partitions=1)
```

---

## 2. Consumer 구현 표준

### 기본 패턴 (단순 메시지)
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class FooConsumer {

    private final FooService fooService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "foo-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws JsonProcessingException {
        FooMessage msg = objectMapper.readValue(payload, FooMessage.class);
        log.info("[Foo] 수신: id={}", msg.id());
        fooService.process(msg);
    }
}
```

> ❌ `throws Exception` 금지 → `throws JsonProcessingException` 으로 좁혀라.  
> `throws Exception`이면 어떤 예외가 나올지 컴파일러가 체크하지 못하고, 예외 분류도 흐려진다.

### Trace 전파가 필요한 패턴 (서비스 간 span 연결 시)
```java
@KafkaListener(topics = "foo-topic", groupId = "${spring.kafka.consumer.group-id}")
public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
    FooMessage msg = objectMapper.readValue(record.value(), FooMessage.class);

    String traceparent = extractTraceparent(record);
    Span span = buildChildSpan(traceparent, "serverX.foo.process");
    try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        fooService.process(msg);
    } catch (Exception e) {
        span.error(e);   // ← 예외를 span에 기록해야 Tempo에서 error로 표시됨
        throw e;
    } finally {
        span.end();
    }
}

private String extractTraceparent(ConsumerRecord<?, ?> record) {
    Header header = record.headers().lastHeader("traceparent");
    return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
}

private Span buildChildSpan(String traceparent, String spanName) {
    if (traceparent == null) {
        return tracer.nextSpan().name(spanName).start();
    }
    return propagator.extract(Map.of("traceparent", traceparent), Map::get).name(spanName).start();
}
```

> `span.error(e)` 없이 예외가 나면 Tempo/Grafana에서 해당 span이 정상으로 보인다.

**판단 기준**: 동일 서비스 내 토픽 소비 → `String payload`. 서비스 간 trace 연결 필요 → `ConsumerRecord`.

### DLT Consumer 패턴
```java
@KafkaListener(
    topics = "foo-topic.DLT",
    groupId = "${spring.kafka.consumer.group-id}.dlt",   // 반드시 .dlt suffix
    containerFactory = "dltKafkaListenerContainerFactory"
)
public void consumeDlt(
        @Payload String payload,
        @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
) {
    log.error("[DLT] foo-topic 처리 실패: reason={}, payload={}", exceptionMessage, payload);
    // DB 저장이 필요하면 Service 메서드를 호출. 여기서 직접 repository 사용 금지.
    // 추가 예외 던지지 말 것 (오프셋 영구 막힘 위험)
}
```

> DLT groupId는 반드시 `${spring.kafka.consumer.group-id}.dlt` 형태로 통일.  
> `-{topic}-dlt` 같은 혼용 금지.

---

## 3. Consumer 레이어 규칙

### ❌ `@Transactional` Consumer 메서드에 직접 붙이기 금지
```java
// 잘못된 패턴
@KafkaListener()
@Transactional          // ← 금지
public void consume(String payload) {
    repository.save();
}
```

```java
// 올바른 패턴
@KafkaListener()
public void consume(String payload) throws JsonProcessingException {
    FooMessage msg = objectMapper.readValue(payload, FooMessage.class);
    fooService.process(msg);   // @Transactional은 Service에
}
```

Consumer는 역직렬화 + 로그 + Service 호출만 한다. 트랜잭션은 Service 레이어에.

---

## 4. Producer 구현 표준

### 임계 경로 동기 발행 (응답 보장 필요 시)
```text
kafkaTemplate.send(TOPIC, partitionKey, payload)
             .get(3, TimeUnit.SECONDS);
```

> **주의: `delivery.timeout.ms: 120000`과 충돌 위험**  
> `get(3s)`에서 `TimeoutException`이 발생해도 백그라운드 재시도는 `delivery.timeout.ms`(2분)까지 계속된다.  
> 즉 예외가 던져졌어도 메시지가 나중에 브로커에 도착할 수 있어 **중복 처리**가 발생할 수 있다.  
> 이 경우를 막으려면 컨슈머에서 멱등 처리(섹션 6)를 반드시 구현해야 한다.

### 비임계 경로 비동기 발행 (실패해도 무방한 경우)
```text
try {
    kafkaTemplate.send(TOPIC, partitionKey, payload);
} catch (Exception e) {
    log.warn("[{}] produce 실패 (무시): key={}, error={}", TOPIC, partitionKey, e.getMessage());
}
```

**결정 규칙**:
- 사용자 요청 처리 중 발행 → 동기 (실패 시 예외 전파)
- 이벤트 스냅샷, 보조 알림 → 비동기 (실패 시 무시)
- `send()` 결과를 완전히 버리는 fire-and-forget 금지. 반드시 catch + 로그

### Partition Key 원칙
- 같은 주문/사용자의 이벤트 순서가 중요하면 → `orderId` 또는 `userId`를 key로
- 순서 무관 이벤트 → `null` key (라운드로빈)
- **이 프로젝트 패턴**: `String.valueOf(userId)` 또는 `orderId` (String 타입)

---

## 5. KafkaConsumerConfig 설정 표준

새 서버에 KafkaConsumerConfig 추가 시 아래 구조를 그대로 복사해서 시작한다.

```java
@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Bean
    public DefaultErrorHandler errorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition())
        );

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners((record, ex, attempt) ->
            log.warn("재시도 #{} → topic={} offset={} error={}",
                attempt, record.topic(), record.offset(), ex.getMessage())
        );

        // 재시도 O (일시적 인프라 오류)
        handler.addRetryableExceptions(ResourceAccessException.class, DataAccessException.class);
        // 재시도 X (코드/데이터 문제)
        handler.addNotRetryableExceptions(
            JsonProcessingException.class,          // JSON 파싱 실패 — 재시도해도 항상 실패
            MessageConversionException.class,
            NullPointerException.class,
            DataIntegrityViolationException.class
        );
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        DefaultErrorHandler dltHandler = new DefaultErrorHandler(
            (record, ex) -> log.error("🚨 [DLT 처리 실패] 오프셋 넘김. payload={}", record.value()),
            new FixedBackOff(0L, 0L)
        );
        dltHandler.setAckAfterHandle(true);
        factory.setCommonErrorHandler(dltHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
```

> `JsonProcessingException`을 명시적으로 NotRetryable에 추가해야 한다.  
> 빠진 경우 JSON 파싱 실패 시 3번 불필요한 재시도 후 DLT로 간다.

---

## 6. KafkaTopicConfig 토픽 설계 규칙

```java
@Bean
public NewTopic fooTopic() {
    return TopicBuilder.name("foo-topic")
                       .partitions(3)
                       .replicas(1)
                       .build();
}

@Bean
public NewTopic fooDltTopic() {
    return TopicBuilder.name("foo-topic.DLT")
                       .partitions(1)
                       .replicas(1)
                       .build();
}
```

**토픽 명명 규칙**:
- 이벤트 토픽: `kebab-case` (예: `payment-request`, `order-status-update`)
- 언더스코어는 CDC/Debezium 토픽에만 사용 (예: `purchase_events`)
- DLT 토픽: 원본 토픽명 + `.DLT`

**파티션 수 결정**:
- 메인 토픽: `concurrency` 값과 일치 (현재 3)
- DLT 토픽: 항상 1
- `replicas`: 개발 단일 브로커 = 1. 운영 환경에서는 3

---

## 7. application.yaml Kafka 설정 표준

새 서버 추가 시 아래 설정을 그대로 사용한다.

```yaml
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all                    # 모든 ISR 복제 확인 후 ack (데이터 유실 방지)
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      properties:
        enable.idempotence: true   # 브로커 재시도 시 중복 발행 방지 (acks=all 필수)
        delivery.timeout.ms: 120000
        linger.ms: 10              # 최대 10ms 배치 대기 (처리량 향상)
        batch.size: 130000
        compression-type: zstd
        request.timeout.ms: 30000
    consumer:
      group-id: server-x-group    # 서버별 고유 그룹 ID
      auto-offset-reset: earliest
      enable-auto-commit: false    # AckMode.RECORD로 수동 커밋
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      properties:
        max.poll.records: 500
        max.poll.interval.ms: 600000  # 500개 처리에 10분 여유. 처리 로직이 무거우면 늘릴 것
    template:
      observation-enabled: true    # KafkaTemplate 발행 시 traceparent 헤더 자동 주입
```

> `enable.idempotence: true`는 `acks: all`과 반드시 함께 써야 한다. 따로 쓰면 설정 충돌로 브로커가 거부한다.  
> `max.poll.records * 처리시간(ms) < max.poll.interval.ms` 조건을 유지해야 리밸런싱 폭풍을 피한다.

---

## 8. 멱등 소비자 (중복 처리 방지)

`AckMode.RECORD` + at-least-once 조합에서 리밸런싱/재시작/동기 발행 타임아웃 시 중복 소비 가능.

**방법 1 - DB 유니크 제약 (권장)**
```java
@Transactional
public void process(FooMessage msg) {
    if (fooRepository.existsByOrderId(msg.orderId())) {
        log.info("[중복 메시지 무시] orderId={}", msg.orderId());
        return;
    }
    // 처리 로직
}
```

**방법 2 - 유니크 인덱스로 DB가 방어**
```java
@Column(unique = true)
private String orderId;
// DataIntegrityViolationException → NotRetryable → 자동 DLT행
```

방법 2는 `DataIntegrityViolationException`이 NotRetryable이므로 errorHandler가 즉시 DLT로 보낸다.

---

## 9. Debezium / 아웃박스 패턴

이 프로젝트의 Debezium CDC 흐름:
```
Service → OutboxEvent 테이블 INSERT (DB 트랜잭션 내)
        → Debezium이 binlog 감지
        → Kafka 토픽으로 자동 발행
```

**아웃박스 vs 직접 KafkaTemplate 선택 기준**:
| 상황 | 방식 |
|------|------|
| DB 저장과 Kafka 발행이 원자성이어야 할 때 | Debezium 아웃박스 |
| DB 트랜잭션 불필요, 빠른 발행이 중요할 때 | KafkaTemplate 직접 |
| 외부 시스템에서 온 이벤트를 중계할 때 | KafkaTemplate 직접 |

> `@Transactional` 메서드 안에서 `KafkaTemplate.send()` 직접 호출 시 트랜잭션 커밋 전에 메시지가 나간다.  
> DB 저장 + 발행의 원자성이 필요하면 반드시 아웃박스 패턴을 써야 한다.

---

## 10. 예외 분류 가이드

새 예외를 errorHandler에 추가해야 할 때 판단 기준:

| 예외 유형 | 분류 | 이유 |
|----------|------|------|
| 네트워크/DB 일시 오류 | Retryable | 재시도 시 성공 가능 |
| `JsonProcessingException` | NotRetryable | 재시도해도 항상 실패 |
| `MessageConversionException` | NotRetryable | 동상 |
| 비즈니스 로직 예외 | NotRetryable (주로) | 데이터 문제, 재시도 무의미 |
| `NullPointerException` | NotRetryable | 코드 버그 |
| `DataIntegrityViolationException` | NotRetryable | 중복 키 등 |
| HTTP 4xx | NotRetryable | 클라이언트 오류 |
| HTTP 5xx | Retryable | 서버 일시 오류 |

분류가 애매하면: **NotRetryable + DLT 수동 처리**가 더 안전하다.

---

## 11. 테스트 작성

### Unit Test: KafkaTemplate 모킹
```java
@ExtendWith(MockitoExtension.class)
class FooServiceTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void 발행_성공() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("foo-topic"), anyString(), anyString())).willReturn(future);
        given(future.get(3, TimeUnit.SECONDS)).willReturn(mock(SendResult.class));
    }

    @Test
    void 발행_타임아웃() throws Exception {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(3, TimeUnit.SECONDS)).willThrow(new TimeoutException("Kafka Timeout"));
    }
}
```

### Unit Test: Consumer 직접 호출
```java
@Test
void consume_정상_처리() throws Exception {
    FooMessage msg = new FooMessage("order-1", "");
    String payload = objectMapper.writeValueAsString(msg);

    fooConsumer.consume(payload);

    verify(fooService).process(any(FooMessage.class));
}
```

Consumer 단위 테스트는 `consume()` 직접 호출. `@EmbeddedKafka`는 통합 테스트에서만.

---

## 12. 자가 점검
- [ ] `throws JsonProcessingException` 으로 좁혔는가? (`throws Exception` 금지)
- [ ] Consumer 메서드에 `@Transactional` 없는가? (Service 레이어에만)
- [ ] Trace Consumer에서 예외 발생 시 `span.error(e)` 를 호출하는가?
- [ ] DLT groupId가 `${spring.kafka.consumer.group-id}.dlt` 형태인가?
- [ ] `JsonProcessingException`이 NotRetryable 목록에 있는가?
- [ ] 동기 `send().get()` 사용 시 컨슈머에서 멱등 처리를 했는가?
- [ ] 새 토픽에 대응하는 `.DLT` 토픽이 `KafkaTopicConfig`에 있는가?
- [ ] DLT 컨슈머에서 추가 예외를 던지지 않는가? (오프셋 막힘 위험)
- [ ] `kafkaTemplate.send()` 결과를 완전히 무시하지 않는가?
- [ ] DB 저장 + 발행 원자성이 필요한 경우 아웃박스 패턴을 썼는가?
- [ ] `setObservationEnabled(true)` 가 ContainerFactory에 설정되어 있는가?
