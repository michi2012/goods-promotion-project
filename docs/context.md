# 맥락 노트: OrderStatusEventHandler 동기화 + SagaTimeoutScheduler 타이밍 조정

## 왜 이 방식을 선택했는가

ServerB는 MySQL이 없어 트랜잭셔널 아웃박스를 쓸 수 없다.
기존 설계는 Kafka send()를 fire-and-forget으로 처리하고 ServerA의 스케줄러가 10분 후 감지하는 방식이었다.
결제 파이프라인에서 10분 감지 지연은 수용하기 어렵고, 실패 시 로그도 없어 디버깅이 불가능했다.

Redis 실패와 Kafka 실패를 같은 catch로 묶는 구조가 문제였다:
- send().get()만 추가하면 TimeoutException이 기존 catch에 걸려 삼켜짐
- Redis 실패(비즈니스 실패) vs Kafka 실패(인프라 실패)는 처리 방식이 다름

**Redis 실패** → Saga에 실패 사실을 알려야 함. fire-and-forget 실패 알림이 적합 (이미 실패한 상황에서 재시도 의미 없음)
**Kafka 실패** → 인프라 일시 오류. 재시도 의미 있음. 예외 전파 → ErrorHandler 재시도 3회 → DLT가 적합

스케줄러는 이제 DLT에서도 놓친 극단적 케이스의 안전망 역할만 하므로 타임아웃을 3분으로 단축했다.

## 검토했으나 채택하지 않은 대안

### 대안 A: ServerB에 MySQL 추가
- 무엇: DB를 붙여 트랜잭셔널 아웃박스 적용
- 왜 안 썼나: DB 추가는 인프라 비용 과도. 현재 구조(Kafka retry + DLT + 스케줄러 3중 안전망)로 충분.

### 대안 B: send() 결과에 whenComplete 콜백 추가
- 무엇: 비동기로 실패 로그만 남기는 방식
- 왜 안 썼나: 로그만으로는 consumer retry/DLT 경로를 활용할 수 없다. 실패가 여전히 silent.

### 대안 C: 스케줄러 interval만 줄이기 (send() 동기화 없이)
- 무엇: 감지 지연만 줄임
- 왜 안 썼나: 근본 문제(실패가 silent)를 해결하지 못함. Kafka 장애 시 여전히 스케줄러만 의존.

## 기존 코드베이스 컨벤션
- 동기 send() 패턴: `serverA/service/PromotionService.java` — `.get(3, TimeUnit.SECONDS)` 사용
- KafkaTemplate mock 패턴: `serverA/test/.../PromotionServiceTest.java` — `CompletableFuture` mock 방식

## 관련 파일/위치
- `serverB/service/OrderStatusEventHandler.java` — 리팩토링 대상
- `serverB/test/.../OrderStatusEventHandlerTest.java` — 테스트 수정
- `serverA/scheduler/SagaTimeoutScheduler.java` — 타이밍 상수 조정
- `serverB/config/KafkaConsumerConfig.java` — 기존 ExponentialBackOff(3회) 설정 확인
