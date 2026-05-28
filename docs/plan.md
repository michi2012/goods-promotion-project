# 계획서: OrderStatusEventHandler 동기화 + SagaTimeoutScheduler 타이밍 조정

- 작성일: 2026-05-28

## 목표
ServerB의 Kafka send() fire-and-forget을 동기화하여 Kafka 실패 시 consumer retry/DLT 경로로 처리되도록 하고,
SagaTimeoutScheduler의 타임아웃을 3분으로 단축하여 장애 감지 시간을 줄인다.

## 성공 기준
- [ ] `OrderStatusEventHandler.handleStatusUpdate()`에서 Redis 성공 후 Kafka send()가 `.get(3s)` 동기 방식으로 호출됨
- [ ] Redis 실패 경로는 기존대로 fire-and-forget 유지 (catch 분리 확인)
- [ ] Kafka send 실패 시 예외가 메서드 밖으로 전파됨 (consumer ErrorHandler가 받아 재시도 가능)
- [ ] `SagaTimeoutScheduler.TIMEOUT_MS = 3분`, `fixedDelay = 30_000`
- [ ] `OrderStatusEventHandlerTest` 전체 통과 (`gradlew :serverB:test` 사용자 실행)

## 비범위 (Out of Scope)
- Redis 실패 경로의 fire-and-forget 개선 (현재 설계 유지)
- DLT consumer 추가 (별도 작업)
- 스케줄러 로직 자체 변경

## 단계별 작업 계획

### 단계 1: OrderStatusEventHandler 리팩토링
- 변경 파일: `serverB/service/OrderStatusEventHandler.java`
- 변경 내용:
  - Redis try-catch를 먼저 처리하고 실패 시 fire-and-forget 실패 알림 후 return
  - Redis 성공 후 Kafka send를 별도 블록으로 분리, `.get(3, TimeUnit.SECONDS)` 추가
  - `throws Exception` 유지 (예외 전파 허용)
- 검증 방법: 컴파일 확인 + 테스트
- 롤백 방법: git restore
- 예상 소요: 짧음

### 단계 2: OrderStatusEventHandlerTest 수정
- 변경 파일: `serverB/test/.../OrderStatusEventHandlerTest.java`
- 변경 내용:
  - 성공 케이스: `kafkaTemplate.send()`가 `CompletableFuture.completedFuture(null)`을 반환하도록 mock 추가. `.get()` 호출 시 NPE 방지.
  - Redis 실패 케이스: fire-and-forget 그대로, mock 수정 불필요
  - Kafka 실패 케이스 추가: send() 결과로 실패한 future 반환 → 예외 전파 검증
- 검증 방법: `gradlew :serverB:test` (사용자 실행)
- 롤백 방법: git restore
- 예상 소요: 짧음

### 단계 3: SagaTimeoutScheduler 타이밍 조정
- 변경 파일: `serverA/scheduler/SagaTimeoutScheduler.java`
- 변경 내용: `TIMEOUT_MS = 3 * 60 * 1000L`, `fixedDelay = 30_000`
- 검증 방법: 상수값 grep 확인
- 롤백 방법: git restore
- 예상 소요: 짧음

## 리스크 및 대응
- `.get(3s)` 추가로 `handleStatusUpdate()` 처리 시간이 최대 3초 늘어남. Kafka 정상 시에는 수십 ms 수준이므로 실질적 영향 없음.
- `fixedDelay = 30s`로 줄이면 Redis SCAN 부하가 2배. 하지만 단일 브로커 + 소규모 키 개수 기준 무시 가능.

## 의존성
- `serverB/config/KafkaConsumerConfig.java`의 ExponentialBackOff(3회, 최대 10s)가 이미 설정되어 있어 Kafka 실패 시 자동 재시도 가능
- `enable.idempotence: true` + `acks: all`이 serverB application.yaml에 설정되어 있어 재시도 중 중복 발행 없음
