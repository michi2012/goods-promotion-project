# 계획서: Redis 물리 분리 + stock-snapshot 복제 구현

- 작성일: 2026-05-23

## 목표
serverA의 쓰기 전용 Redis(6379)와 serverB의 읽기 전용 복제 Redis(6380)를 물리 분리한다. serverA는 PromotionService의 Lua 차감 직후와 Saga 보상 시 `stock-snapshot` Kafka 이벤트를 fire-and-forget으로 produce하고, serverB는 이를 자신의 Redis에 저장하여 재고 조회 및 주문 상태 조회 API를 제공한다. Saga의 `status-update-result` 라운드트립은 변경하지 않는다.

## 성공 기준
- [ ] docker-compose 기동 시 Redis 컨테이너 2개(weverse-redis, weverse-redis-b)가 독립적으로 동작한다
- [ ] PromotionService의 `reserveStock` 성공 후 `stock-snapshot` 이벤트가 produce된다 (serverA 로그로 확인)
- [ ] serverB `GET /api/v1/goods/{goodsId}/stock`이 복제된 재고 수치를 반환한다
- [ ] serverB `GET /api/v1/orders/{traceId}/status`가 주문 상태를 반환한다
- [ ] `gradlew.bat :serverA:build :serverB:build` 통과

## 비범위
- Saga 흐름(status-update-result, statusUpdateCompleted 플래그): 변경 없음
- Redis AOF/RDB 영속성 설정
- stock-snapshot DLT 상세 처리 (serverB 공통 DLT 핸들러에 위임)
- 재고 조회 응답 캐싱/TTL 전략

## 단계별 작업 계획

### 단계 1: 인프라 — Redis 물리 분리
- 변경 파일: `docker-compose.yml`, `serverB/src/main/resources/application.yaml`
- 변경 내용: `redis-b` 컨테이너(호스트 포트 6380) 추가. server-b 서비스의 환경변수를 `SPRING_DATA_REDIS_HOST: redis-b`로 변경. `application.yaml` 로컬 기본 포트를 6380으로 변경.
- 검증: `docker-compose config` 문법 오류 없음 확인
- 롤백: `git restore docker-compose.yml serverB/src/main/resources/application.yaml`
- 예상 소요: 짧음

### 단계 2: serverA — stock-snapshot 발행
- 변경 파일:
  - 신규: `serverA/.../dto/StockSnapshotMessage.java`
  - 수정: `serverA/.../service/RedisStockService.java` — `getCurrentStock(goodsId)` 추가
  - 수정: `serverA/.../service/PromotionService.java` — `reserveStock` 성공 후 fire-and-forget produce
  - 수정: `serverA/.../service/SagaOrchestratorService.java` — `releaseStock` 후 fire-and-forget produce
- 변경 내용: `StockSnapshotMessage(Long goodsId, Long remainingStock)` record 신규. PromotionService는 동기 경로이므로 `.get()` 없이 순수 비동기 produce. SagaOrchestratorService도 동일 패턴.
- 검증: `gradlew.bat :serverA:compileJava`
- 롤백: `git restore serverA/`
- 예상 소요: 보통

### 단계 3: serverB — stock-snapshot 수신 + 재고/주문 조회 API
- 변경 파일:
  - 신규: `serverB/.../dto/StockSnapshotMessage.java`
  - 신규: `serverB/.../kafka/StockSnapshotConsumer.java`
  - 수정: `serverB/.../service/OrderQueryService.java` — 키 구조 변경 + `getStockView` 추가
  - 수정: `serverB/.../controller/OrderQueryController.java` — URL 변경 + 재고 API 추가
- 변경 내용:
  - `StockSnapshotConsumer`: `goods:view:stock:{goodsId}` 업데이트
  - `OrderQueryService`: 키를 `order:view:{traceId}:status`로 변경. `updateOrderStatus`에서 userId 파라미터 제거(키에 불필요). `updateStockView` / `getStockView` 추가.
  - `OrderQueryController`: `GET /api/v1/orders/{traceId}/status` + `GET /api/v1/goods/{goodsId}/stock`
- 검증: `gradlew.bat :serverB:compileJava`
- 롤백: `git restore serverB/`
- 예상 소요: 보통

### 단계 4: Kafka 토픽 등록 + 전체 빌드
- 변경 파일: serverA `KafkaConsumerConfig.java` (또는 토픽 빈 위치 확인 후)
- 변경 내용: `stock-snapshot` NewTopic 빈 등록. `KAFKA_AUTO_CREATE_TOPICS_ENABLE: false`이므로 필수.
- 검증: `gradlew.bat build`
- 롤백: `git restore`
- 예상 소요: 짧음

## 리스크 및 대응
- **stock-snapshot 순서 역전**: 고속 선착순 환경에서 이벤트 순서가 뒤바뀔 수 있음. serverB 재고 뷰는 eventual consistency 허용 (display 목적). 파티셔닝 키를 goodsId로 고정하면 완화됨.
- **PromotionService fire-and-forget 유실**: Kafka 장애 시 stock-snapshot 미전송 → serverB stale. display 목적이므로 허용.
- **stock-snapshot 토픽 미생성**: 단계 4에서 반드시 NewTopic 빈 등록. 단계 2 실행 전엔 produce 자체는 에러 없이 실패하므로 컴파일엔 영향 없음.
- **OrderStatusKafkaConsumer userId 파라미터 불일치**: `updateOrderStatus` 시그니처 변경 시 컴파일 에러 발생. 단계 3에서 Consumer 콜사이트도 함께 수정.

## 의존성
- serverB KafkaConsumerConfig: `kafkaTemplate` 빈은 status-update-result produce로 이미 사용 중. stock-snapshot DLT도 동일 설정으로 자동 처리.
- `KAFKA_AUTO_CREATE_TOPICS_ENABLE: false` → 토픽 빈 없으면 produce 시 에러.
