# 체크리스트: Kafka 코드 5가지 문제 수정

- 마지막 업데이트: 2026-05-28

## 진행 상황
- [x] 단계 1: JsonProcessingException NotRetryable 추가 (3개 서버)
  - [x] serverA KafkaConsumerConfig 확인
  - [x] serverB KafkaConsumerConfig 확인
  - [x] serverC KafkaConsumerConfig 확인
- [x] 단계 2: PurchaseDltConsumer 리팩토링
  - [x] @Transactional 제거 확인
  - [x] DeadLetterRepository 의존 제거, DeadLetterService 주입 확인
  - [x] PurchaseDltConsumerTest — DeadLetterService mock으로 교체 + 파싱 실패 케이스 추가
- [x] 단계 3: OrderCommandService 멱등 처리
  - [x] findByOrderId 중복 체크 추가 확인
  - [x] OrderCommandServiceTest — 중복 메시지 케이스 추가
- [x] 단계 4: PaymentCancelConsumer DLT groupId + span.error
  - [x] groupId `.dlt` suffix 확인 (payment-cancel-dlt → .dlt)
  - [x] span.error(e) + rethrow 확인
- [x] 단계 5: PaymentKafkaConsumer, OrderCompletedConsumer span.error
  - [x] PaymentKafkaConsumer span.error(e) 확인
  - [x] OrderCompletedConsumer span.error(e) 확인

## 최종 검증
- [ ] `gradlew :serverA:test` 통과 (사용자 실행)
- [ ] `gradlew :serverC:test` 통과 (사용자 실행)
- [x] 비범위 항목 침범 없음
- [x] git diff로 변경 범위 최종 확인

## 발견 사항
- (없음)
