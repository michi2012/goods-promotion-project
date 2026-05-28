# 체크리스트: OrderStatusEventHandler 동기화 + SagaTimeoutScheduler 타이밍 조정

- 마지막 업데이트: 2026-05-28

## 진행 상황
- [x] 단계 1: OrderStatusEventHandler 리팩토링
  - [x] Redis 실패 경로와 Kafka send 경로 분리 확인
  - [x] Kafka send에 .get(3, TimeUnit.SECONDS) 추가 확인
  - [x] 예외가 메서드 밖으로 전파되는 구조 확인
- [x] 단계 2: OrderStatusEventHandlerTest 수정
  - [x] 성공 케이스: CompletableFuture.completedFuture(null) mock 추가
  - [x] Kafka 실패 케이스(handleStatusUpdate_Pending_KafkaTimeout) 추가
  - [ ] `gradlew :serverB:test` 통과 (사용자 실행)
- [x] 단계 3: SagaTimeoutScheduler 타이밍 조정
  - [x] TIMEOUT_MS = 3 * 60 * 1000L 확인
  - [x] fixedDelay = 30_000 확인

## 최종 검증
- [ ] `gradlew :serverB:test` 통과 (사용자 실행)
- [x] 비범위 항목 침범 없음
- [x] git diff로 변경 범위 최종 확인

## 발견 사항
- (없음)
