# 맥락 노트: DB Polling → CDC (Debezium) 전환

## 왜 이 방식을 선택했는가

기존 `OutboxRelayScheduler`는 500ms마다 `SELECT FOR UPDATE SKIP LOCKED`로 PENDING 이벤트를
폴링해 `KafkaTemplate`으로 직접 전송한다. 이전 작업에서 이미 "CDC 전환 시 RelayScheduler만
제거하면 되도록" 설계해 두었으므로, `OutboxEventService.save()`는 변경 없이 재사용된다.

CDC 방식: MySQL binlog를 Debezium이 직접 읽어 INSERT 발생 즉시 Kafka로 전달.
Spring 앱은 DB에 이벤트를 저장하는 역할만 하고, 전파는 인프라(Debezium)가 담당한다.

## Debezium Outbox Event Router SMT 동작 원리

```
MySQL binlog  →  Debezium MySQL Connector  →  [EventRouter SMT]  →  Kafka
                                                      │
                 outbox_event INSERT 감지
                 ├── key:   aggregate_id 컬럼 값
                 ├── value: payload 컬럼 값 (JSON 그대로 전달)
                 └── topic: topic 컬럼 값 (예: "payment-request")
```

핵심 SMT 설정:
- `route.by.field=topic` — outbox_event.topic 컬럼으로 Kafka 토픽 결정
- `table.field.event.key=aggregate_id` — Kafka message key
- `table.field.event.payload=payload` — Kafka message value
- `table.op.invalid.behavior=skip` — UPDATE/DELETE CDC 이벤트 무시

downstream consumer(Server B 등)가 수신하는 Kafka 메시지 포맷은 변경 없음.
EventRouter SMT가 payload 컬럼 값을 그대로 Kafka value로 전달하기 때문.

## 검토했으나 채택하지 않은 대안

### 대안 A: confluentinc/cp-kafka-connect 이미지
- 무엇: Confluent Platform 공식 Kafka Connect 이미지
- 왜 안 썼나: MySQL Debezium connector를 별도 설치해야 함. `debezium/connect:2.7`은 MySQL connector 내장.

### 대안 B: status 필드 유지 (PENDING 고정)
- 무엇: status/sentAt 필드를 그대로 두고 코드만 단순화
- 왜 안 썼나: CDC 전환 후 레코드가 영원히 PENDING 상태로 남아 의미 없음. 사용자가 제거 선택.

### 대안 C: EventRouter SMT 없이 일반 Debezium connector
- 무엇: 표준 connector → `dbz.weverse_promo.outbox_event` 단일 토픽 → 별도 소비자가 재라우팅
- 왜 안 썼나: downstream consumer 코드 변경 필요. EventRouter로 topic 컬럼 직접 라우팅하면 소비자 변경 0.

### 대안 D: 커넥터 수동 등록
- 무엇: docker-compose에 init container 없이 사용자가 직접 curl로 등록
- 왜 안 썼나: 사용자가 완전 자동화 선택.

## status/sentAt 제거 이유

CDC는 INSERT 이벤트만 캡처해 Kafka로 전달한다. 폴링 방식처럼 PENDING → PUBLISHING → SENT
상태 전이가 필요 없다. cleanup은 `created_at < 24h ago` 기준으로 단순화한다.
Docker 재시작 시 DB가 재생성되므로 스키마 마이그레이션 추가 불필요.

## snapshot.mode=schema_only 선택 이유

- `initial`: 기존 DB의 모든 outbox_event 레코드를 스냅샷으로 재발행 → 이미 처리된 이벤트 중복 발행 위험
- `schema_only`: 스키마만 캡처 후 binlog 스트리밍 시작 → Debezium 기동 이후 새 INSERT만 처리
- Docker 환경 재시작 시 DB 초기화되므로 실제 차이는 없지만, schema_only가 의미론적으로 안전

## 기존 코드베이스 컨벤션

- 디렉토리: `serverA/src/main/java/weverse/serverA/outbox/`
- 스케줄러: `@Scheduled` + `TransactionTemplate` (Spring Transaction 관리 일관성)
- 테스트: `@ExtendWith(MockitoExtension.class)` Mockito 단위 테스트
- SchedulingConfig: Server A - pool 3 (Saga 타임아웃 스케줄러 등 존재), Server C - cleanup 전용

## 관련 파일/위치

- `docker-compose.yml` — 전체 인프라 정의
- `debezium/outbox-connector.json` — Debezium 커넥터 설정 (신규)
- `serverA/.../outbox/` — Server A outbox 패키지
- `serverC/.../outbox/` — Server C outbox 패키지 (동일 구조)
- `serverA/.../config/SchedulingConfig.java` — 스케줄러 스레드풀 (pool 4→3으로 복원)

## 외부 참조

- Debezium Outbox Event Router SMT: https://debezium.io/documentation/reference/2.7/transformations/outbox-event-router.html
- Debezium MySQL Connector snapshot.mode: https://debezium.io/documentation/reference/2.7/connectors/mysql.html#mysql-property-snapshot-mode
