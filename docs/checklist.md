# 체크리스트: 트랜잭셔널 아웃박스 패턴 도입

- 마지막 업데이트: 2026-05-24

## 진행 상황

- [x] **단계 1**: ServerA Outbox 인프라 + Relay 스케줄러 신규 파일 작성
  - [x] `outbox/OutboxStatus.java` 생성
  - [x] `outbox/OutboxEvent.java` 생성 (BaseTimeEntity 상속)
  - [x] `outbox/OutboxEventRepository.java` 생성 (SKIP LOCKED 쿼리 포함)
  - [x] `outbox/OutboxEventService.java` 생성
  - [x] `outbox/OutboxRelayScheduler.java` 생성 (2초 폴링 + 1시간 cleanup)
  - [x] `config/SchedulingConfig.java` pool size 3 -> 4 수정
  - [x] 검증: `.\gradlew.bat :serverA:compileJava` 통과

- [x] **단계 2**: ServerA 기존 코드 Outbox로 전환
  - [x] `OrderCommandService.java` — OutboxEventService 주입, 2개 이벤트 저장
  - [x] `SagaOrchestratorService.java` — KafkaTemplate 제거, OutboxEventService 전환
  - [x] `PurchaseKafkaConsumer.java` — KafkaProduceService 의존성 제거
  - [x] `kafka/KafkaProduceService.java` 삭제
  - [x] `KafkaProduceServiceTest.java` 삭제
  - [x] `PurchaseKafkaConsumerTest.java` — KafkaProduceService mock 제거
  - [x] `SagaOrchestratorServiceTest.java` — OutboxEventService mock으로 교체
  - [x] `OrderCommandServiceTest.java` — OutboxEventService mock 추가
  - [x] 검증: `.\gradlew.bat :serverA:build` 통과 (수정된 테스트 3개 모두 통과)

- [x] **단계 3**: ServerC Outbox 인프라 + Relay 스케줄러 신규 파일 작성
  - [x] `outbox/OutboxStatus.java` 생성
  - [x] `outbox/OutboxEvent.java` 생성
  - [x] `outbox/OutboxEventRepository.java` 생성
  - [x] `outbox/OutboxEventService.java` 생성
  - [x] `outbox/OutboxRelayScheduler.java` 생성
  - [x] `config/SchedulingConfig.java` 생성 (@EnableScheduling)
  - [x] 검증: `.\gradlew.bat :serverC:compileJava` 통과

- [x] **단계 4**: ServerC PaymentService Outbox로 전환
  - [x] `PaymentService.java` — @Transactional 추가, KafkaTemplate 제거, OutboxEventService 주입
  - [x] `PaymentServiceTest.java` — OutboxEventService mock으로 교체
  - [x] 검증: `.\gradlew.bat :serverC:build` 통과

- [x] **단계 5**: 전체 빌드 최종 검증
  - [x] 검증: `.\gradlew.bat build` — ServerA의 사전 존재하는 AppConfigTest(1개) 제외 전체 통과

## 최종 검증

- [x] 모든 단위 테스트 통과 (AppConfigTest는 내 변경 전부터 실패하는 사전 버그)
- [x] plan.md 비범위(ServerB, CDC, FAILED 상태) 침범 없음
- [x] build.gradle 의존성 추가 없음 (기존 JPA, Kafka 활용)
- [x] git diff --stat으로 의도하지 않은 파일 변경 없음 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)

- **AppConfigTest 사전 버그**: `serverA/src/test/.../AppConfigTest.java:21`이 `containsBean("appConfig")`를 assert하지만, serverA에 AppConfig 클래스가 존재하지 않아 항상 실패. 내 변경 전부터 존재하는 버그.
