# 맥락 노트: serverC payment-cancel DLT 기록 실패 대비 (BL-034)

## [진행 중] BL-034

### 왜 이 방식을 선택했는가
- PR #48(BL-029 PR①) 리뷰 C1 [필수]: DLT 팩토리가 `FixedBackOff(0,0)` + 로그만 남기는 복구 함수라, `recordDltArrival` DB 쓰기가 한 번 실패하면 그 취소 건이 자동 재시도에서도, 알람에서도 사라진다. 사용자 환불(BL-029 PR②)이 이 경로를 쓰므로 그 전에 막는다.
- 사용자 결정 (2026-11-16):
  - C1: 횟수 제한 백오프(지수 5회, 약 31초) + 최종 실패 메트릭을 P0에 OR (A안)
  - C2: 새로 EXHAUSTED가 될 때만 P0 메트릭 증가
  - C4: RESOLVED → RETRYING 시 attempts=0 (새 장애로 보고 자동 재시도 6회를 다시 준다). 명세 표(`user-refund.md` "attempts 유지")를 함께 바꾼다
- `refundFatal`("자동 재시도 진입" 건수)을 기록 성공 뒤로 옮기는 이유: DLT 핸들러가 재시도되면 앞에서 올린 카운터가 재시도 횟수만큼 중복된다.

### 검토했으나 채택하지 않은 대안
#### 무한 재시도 (DB 복구까지 대기)
- 왜 안 썼나: 해결되지 않는 메시지 하나가 DLT 처리 전체를 막고, 막힌 동안 알람이 없다.
#### 일반 이름 + topic 태그 카운터 (`kafka_dlt_handler_failed_total{topic}`) — Step 1 가정에서 변경
- 왜 안 썼나: 태그 시리즈는 첫 실패 때 생겨서 Prometheus `increase()`가 첫 증가를 놓친다. 미리 등록하려면 토픽을 하드코딩해야 해 일반화 이점이 없다. 이 팩토리를 쓰는 리스너는 하나뿐이라 기존 `business_payment_*` 이름 규칙의 전용 카운터를 빈 생성 시 등록한다.

### 기존 코드베이스 컨벤션
- 메트릭: `business_*_total`, `@PostConstruct`에서 `Counter.builder(...).register(meterRegistry)` (`PaymentCancelService.java`, `PaymentCancelConsumer.java`)
- 트랜잭션: 서비스에서 `TransactionTemplate`으로 짧게 묶음, PG 호출은 트랜잭션 밖
- 테스트: Mockito 단위 + `SimpleMeterRegistry` (`PaymentCancelServiceTest.java`), 엔티티 순수 단위 (`PaymentCancelFailureTest.java`), Testcontainers 통합 (`PaymentCancelRetryIntegrationTest.java`)
- 알람 규칙은 `monitoring/`(로컬)과 `helm/promotion-monitoring/files/`(k8s) 두 벌을 같이 수정

### 관련 파일/위치
- `serverC/src/main/java/promotion/serverC/config/KafkaConsumerConfig.java` — 메인·DLT 리스너 팩토리
- `serverC/src/main/java/promotion/serverC/kafka/PaymentCancelConsumer.java` — `payment-cancel`·`payment-cancel.DLT` 리스너
- `serverC/src/main/java/promotion/serverC/service/PaymentCancelService.java` — 취소·DLT 기록·자동/관리자 재시도
- `serverC/src/main/java/promotion/serverC/entity/PaymentCancelFailure.java` — 실패 행 상태 전이
- `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml` — P0 `PaymentFatalError`
- `docs/specs/user-refund.md` — 상태 전이 표(146-161행), 장애 시 동작(251-260행), 변경 이력

### 외부 참조
- `.claude/skills/kafka` (DLT 설계), Spring Kafka `DefaultErrorHandler`·`ExponentialBackOffWithMaxRetries`
