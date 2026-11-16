# 맥락 노트: Kafka DLT 발행 파티션 고정 해제 (BL-037)

## [진행 중] BL-037

### 왜 이 방식을 선택했는가
- BL-029 PR②-b 단계 5(`payment-cancelled` 컨슈머 + `payment-cancelled.DLT`) 착수 중, serverA·B·C 공통 `KafkaConsumerConfig.errorHandler()`의 DLT 대상이 `new TopicPartition(topic + ".DLT", record.partition())`인 것을 발견했다. 원본 토픽은 파티션 3, DLT 토픽은 모두 파티션 1이라 원본 파티션 1·2의 레코드는 DLT 발행에 실패한다 (코드로 판단, 미재현).
- 사용자 결정 (2026-11-16): ②-b를 멈추고 main에서 별도 긴급 PR로 먼저 처리 / 방식 A (대상 파티션 -1).
- spring-kafka 3.3.15 `DeadLetterPublishingRecoverer`는 대상 파티션이 음수면 `ProducerRecord`에 파티션을 넣지 않아 프로듀서 파티셔너가 고른다. 원본 토픽·파티션·오프셋·예외는 DLT 헤더(`kafka_dlt-original-*`, `kafka_dlt-exception-*`)로 남는다.

### 검토했으나 채택하지 않은 대안
#### B. DLT 토픽 파티션을 원본과 같게 (3)
- 왜 안 썼나: DLT 토픽 선언 여러 개 + 기존 토픽 파티션 증가가 필요하고, 원본 파티션이 늘면 다시 어긋난다. 프로젝트 관례(`.claude/skills/kafka` — DLT 파티션 1)와도 다르다.
#### 고정 파티션 0
- 왜 안 썼나: 지금은 같은 결과지만 DLT 파티션을 늘리면 쏠린다. -1이 의도(파티션은 상관없음)를 그대로 드러낸다.

### 기존 코드베이스 컨벤션
- 세 서버 `config/KafkaConsumerConfig.java`가 같은 구조 (메인 `errorHandler()` + `dltKafkaListenerContainerFactory`)
- serverC에는 BL-034에서 만든 `KafkaConsumerConfigTest`(DLT 리스너 에러 핸들러 테스트)가 있다 — 같은 클래스에 메인 에러 핸들러 테스트를 추가

### 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/config/KafkaConsumerConfig.java`
- `serverB/src/main/java/promotion/serverB/config/KafkaConsumerConfig.java`
- `serverC/src/main/java/promotion/serverC/config/KafkaConsumerConfig.java`
- 원본·DLT 토픽 선언: 각 서버 `config/KafkaTopicConfig.java`

### 보류 중인 작업
- BL-029 PR②-b: 단계 1~4 완료분이 `git stash`(`BL-029 PR②-b WIP (단계 1~4 완료, 단계 5 착수 전)`)에 있다. 브랜치 `feature/bl-029-refund-servera`. 재개 절차는 plan.md 리스크 항목.
