---
description: 프로젝트 전체를 스캔해 docs/arch-snapshot.md를 생성한다. codex-review 실행 전 선행 실행 권장.
---

# /arch-snapshot 명령어

이 명령어를 실행하면 아래 순서대로 수행한다.

---

## Step 1. 프로젝트 스캔

다음 파일들을 순서대로 읽는다. 파일이 없으면 건너뛴다.

### 1-1. 모듈 목록
루트의 `settings.gradle` 또는 `settings.gradle.kts`를 읽어 모듈 목록을 확인한다.

### 1-2. Kafka 토픽 및 메시지 흐름
각 모듈의 `*KafkaTopicConfig.java`, `*KafkaConsumerConfig.java`, `*KafkaConsumer.java`, `*Consumer.java` 파일을 읽는다.
- 정의된 토픽명 (TopicBuilder.name)
- 각 Consumer가 구독하는 토픽 (`@KafkaListener topics`)
- 각 Producer가 발행하는 토픽 (`kafkaTemplate.send`)

### 1-3. 주요 엔티티
각 모듈의 `entity/` 하위 `.java` 파일을 읽어 엔티티명, 핵심 필드, 연관관계를 파악한다.

### 1-4. 서비스 레이어
각 모듈의 `service/` 하위 `.java` 파일 목록을 파악한다. 파일명만 확인하고 내용은 핵심 공개 메서드명만 추출한다.

### 1-5. API 엔드포인트 및 보안 경계
각 모듈의 `controller/` 하위 `.java` 파일을 읽어 HTTP 메서드와 URL 매핑을 추출한다 (`@GetMapping`, `@PostMapping` 등).
- `SecurityConfig`, `*Filter.java` 파일이 있으면 읽어 인증 필요 여부(퍼블릭 / 인증 필요)를 엔드포인트별로 표기한다.
- Security 설정 파일이 없으면 "인증 설정 없음 (전체 퍼블릭으로 추정)" 으로 표기한다.

### 1-6. 인프라 설정
각 모듈의 `application.yaml` (또는 `application.yml`)에서 다음 항목만 추출한다:
- `server.port`
- `spring.datasource.url` (DB명만)
- `spring.kafka.bootstrap-servers`
- `spring.data.redis` 유무
- `external.*` (서버 간 HTTP 호출 대상)

### 1-7. Debezium CDC 설정
`debezium/outbox-connector.json`이 존재하면 읽어서 다음을 추출한다:
- `database.include.list` (감시 대상 DB)
- `table.include.list` (감시 대상 테이블)
- `topic.prefix` 및 라우팅 방식 (`transforms.outbox.route.by.field`)

### 1-8. Redis 사용 구조
각 모듈의 `*RedisService.java`, `RedisTemplate` 사용 파일, `@Cacheable` 어노테이션 사용 파일을 읽어 파악한다:
- 어떤 데이터를 Redis에 저장하는지 (키 패턴)
- TTL 설정 여부 (`expire`, `Duration` 사용 유무)
- 캐시 용도인지 주 저장소 용도인지 구분

### 1-9. 스케줄러 및 배치 잡
각 모듈의 `*Scheduler.java`, `@Scheduled` 어노테이션 사용 파일을 읽어 파악한다:
- 잡 이름과 실행 주기 (`fixedDelay`, `cron` 표현식)
- 어떤 작업을 수행하는지 (한 줄 요약)

### 1-10. 외부 시스템 의존성
각 모듈에서 내부 서버 간 호출이 아닌 외부 3rd party 연동 지점을 파악한다:
- PG사, 문자/이메일 발송, 외부 API 호출 파일 (`*Client.java`, `*Gateway.java`, `WebClient`, `RestTemplate` 사용)
- 연동 실패 시 처리 방식 (Circuit Breaker, retry 여부)

### 1-11. 예외 처리 토폴로지
각 모듈의 `GlobalExceptionHandler.java`, `*Exception.java` 파일 목록을 파악한다:
- 모듈별 공통 예외 핸들러 유무
- 주요 커스텀 예외 종류 (한 줄 설명)

---

## Step 2. docs/arch-snapshot.md 생성

스캔 결과를 바탕으로 아래 형식으로 `docs/arch-snapshot.md`를 작성한다.
**파일이 이미 존재하면 덮어쓴다.**

````markdown
# Architecture Snapshot
_생성일: YYYY-MM-DD_

## 모듈 구성

| 모듈 | 포트 | 역할 | DB | Redis |
|------|------|------|----|-------|
| serverA | 8080 | ... | promotion DB | ✅ |
| ... | | | | |

## Kafka 토픽 맵

| 토픽명 | 발행 모듈 | 소비 모듈 | 파티션 | DLT |
|--------|-----------|-----------|--------|-----|
| purchase_events | serverA | serverA | 3 | ✅ |
| ... | | | | |

## 전체 이벤트 흐름 다이어그램

```mermaid
sequenceDiagram
    participant Client
    participant serverA
    participant Kafka
    participant serverB
    participant serverC
    ...
```

## 서버 간 이벤트 흐름 (텍스트)

(발행자 → 토픽 → 소비자 형태로 각 흐름을 한 줄씩 기술)

## 주요 엔티티

| 모듈 | 엔티티 | 핵심 필드 |
|------|--------|-----------|
| serverA | Order | id, status, goodsId, userId |
| ... | | |

## API 엔드포인트 및 보안 경계

### serverA (8080)
| 메서드 | URL | 설명 | 인증 |
|--------|-----|------|------|
| POST | /api/v1/promotions/purchase | 구매 요청 | 퍼블릭 / 인증필요 |

### serverB (8081)
| 메서드 | URL | 설명 | 인증 |
|--------|-----|------|------|

### serverC (8082)
- 외부 Kafka 소비만, HTTP 엔드포인트 없음

## Redis 사용 구조

| 모듈 | 키 패턴 | 저장 데이터 | TTL | 용도 |
|------|---------|------------|-----|------|
| serverA | stock:{goodsId} | 재고 수량 | 없음 | 주 저장소 |
| ... | | | | |

## 스케줄러 및 배치 잡

| 모듈 | 클래스명 | 주기 | 역할 |
|------|----------|------|------|
| serverA | SagaTimeoutScheduler | fixedDelay=... | Saga 타임아웃 감지 및 보상 트랜잭션 |
| ... | | | |

## 외부 시스템 의존성

| 모듈 | 클래스 | 대상 시스템 | Circuit Breaker | 실패 처리 |
|------|--------|------------|-----------------|-----------|
| serverC | PgClient | PG사 결제 API | ✅ pgClientCb | 재시도 후 payment-cancel 발행 |

## 예외 처리 토폴로지

| 모듈 | GlobalExceptionHandler | 주요 커스텀 예외 |
|------|------------------------|-----------------|
| serverA | ✅ | SoldOutException, DuplicateOrderException, ... |
| ... | | |

## 서버 간 HTTP 호출

| 호출자 | 대상 | Circuit Breaker | 용도 |
|--------|------|-----------------|------|
| serverA | serverB (8081) | serverBClient | ... |

## Debezium CDC 구간
codex-reviewer 참고용. debezium/outbox-connector.json에서 추출.
- 감시 DB/테이블: ...
- 라우팅 방식: outbox_event.topic 필드 값 → Kafka 토픽명으로 동적 라우팅
- Outbox 패턴 사용 모듈: (코드에서 OutboxEvent* 파일이 존재하는 모듈 기재)
````

---

## Step 3. 완료 보고

생성 완료 후 다음 형식으로 보고한다:

```
✅ docs/arch-snapshot.md 생성 완료
- 모듈: N개
- Kafka 토픽: N개
- API 엔드포인트: N개
- 스케줄러: N개
- 외부 시스템: N개
- 다음 단계: /codex-review 실행 가능
```
