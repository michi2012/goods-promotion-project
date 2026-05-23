# 체크리스트: Redis 물리 분리 + stock-snapshot 복제 구현

- 마지막 업데이트: 2026-05-23

## 진행 상황

- [x] 단계 1: 인프라 — Redis 물리 분리
  - [x] docker-compose.yml: redis-b 컨테이너 추가
  - [x] docker-compose.yml: server-b 환경변수 SPRING_DATA_REDIS_HOST → redis-b
  - [x] serverB/application.yaml: 로컬 Redis 기본 포트 6380으로 변경
  - [x] 검증: `docker-compose config` — EXIT:0

- [x] 단계 2: serverA — stock-snapshot 발행
  - [x] StockSnapshotMessage.java 신규 생성
  - [x] RedisStockService.java: getCurrentStock 추가
  - [x] PromotionService.java: reserveStock 성공 후 fire-and-forget produce
  - [x] SagaOrchestratorService.java: releaseStock 후 produce
  - [x] 검증: `gradlew.bat :serverA:compileJava` — BUILD SUCCESSFUL

- [x] 단계 3: serverB — stock-snapshot 수신 + 조회 API
  - [x] StockSnapshotMessage.java 신규 생성
  - [x] StockSnapshotConsumer.java 신규 생성
  - [x] OrderQueryService.java: traceId 기반 키 + stock 뷰 메서드 추가
  - [x] OrderQueryController.java: GET /api/v1/orders/{traceId}/status + GET /api/v1/goods/{goodsId}/stock
  - [x] OrderStatusKafkaConsumer.java: updateOrderStatus 콜사이트 시그니처 일치 확인
  - [x] 검증: `gradlew.bat :serverB:compileJava` — BUILD SUCCESSFUL

- [x] 단계 4: Kafka 토픽 등록 + 전체 빌드
  - [x] serverA KafkaConsumerConfig: stock-snapshot NewTopic 빈 등록
  - [x] 검증: `gradlew.bat build` — BUILD SUCCESSFUL in 2m 14s

## 최종 검증
- [x] `gradlew.bat build` 전체 통과
- [x] Saga 흐름(status-update-result, statusUpdateCompleted) 변경 없음 확인
- [x] 의도하지 않은 파일 변경 없는지 git diff 확인

## 발견 사항
- serverB KafkaConsumerConfig DLT 핸들러는 토픽명 자동 라우팅 → stock-snapshot.DLT 별도 설정 불필요
