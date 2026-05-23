# 맥락 노트: Redis 물리 분리 + stock-snapshot 복제

## 왜 이 방식을 선택했는가

Redis는 싱글 스레드로 명령을 처리한다. serverA와 serverB가 동일한 Redis를 공유하면, 선착순 트래픽에서 쏟아지는 조회 요청이 Redis 자원을 선점하여 가장 중요한 Lua 재고 차감 연산이 타임아웃으로 실패할 수 있다.

해결 원칙: 쓰기(serverA)와 읽기(serverB)를 물리적으로 완전히 단절.

- serverA Redis (6379): 재고 Lua 차감, SagaState, 유저 중복 방어. 원본 데이터 전용. 조회 트래픽 차단.
- serverB Redis (6380): 복제 뷰 데이터 전용. 수많은 새로고침·조회 트래픽 흡수.

복제 수단으로 Kafka를 선택한 이유: 이미 두 서버 간 Kafka 채널이 존재하고, 추가 인프라 없이 이벤트 기반 복제가 가능하다.

## 검토했으나 채택하지 않은 대안

### 대안 A: Redis Replica (Read Replica) 구성
- 무엇: Redis 자체의 replication 기능으로 slave 노드를 serverB 전용으로 운영
- 왜 안 썼나: 요구사항이 Kafka 이벤트 기반 복제. 또한 slave는 master와 동일 키 구조를 그대로 노출하므로 키 분리 설계 불가.

### 대안 B: serverA Redis에 DB 번호 분리 (DB 0 / DB 1)
- 무엇: 같은 Redis 인스턴스에서 `SELECT 1`로 읽기용 DB 분리
- 왜 안 썼나: 싱글 스레드 문제가 해결되지 않음. 조회 트래픽이 여전히 같은 이벤트 루프를 점유.

### 대안 C: status-update-result 라운드트립 제거 (Saga 단순화)
- 무엇: serverB가 결과 이벤트를 produce하지 않고, Saga는 payment-result만으로 완료 판단
- 왜 안 썼나: serverA가 "두 작업 모두 완료"를 확인하여 원자성을 보장해야 한다는 요구사항 유지 결정.

## 핵심 설계 결정

### stock-snapshot 발행 시점: PromotionService (Lua 차감 직후)
- PurchaseKafkaConsumer(DB INSERT 후) 대비 더 빠른 복제
- Kafka produce 실패나 Saga 롤백 시 serverB 재고가 stale 상태 유지 가능하지만, 조회용 뷰이므로 eventual consistency 허용

### stock-snapshot 발행 추가 시점: SagaOrchestratorService.handleSagaFailure
- 보상 트랜잭션으로 재고가 복구될 때도 serverB에 반영해야 뷰가 stale 상태로 고착되지 않음

### serverB Redis 키 구조
```
goods:view:stock:{goodsId}       — 재고 뷰 (Long, 문자열 저장)
order:view:{traceId}:status      — 주문 상태 뷰 (PENDING / PAID / FAILED / EXPIRED)
```

### stock-snapshot 파티셔닝 키: goodsId
- 동일 goodsId에 대한 이벤트가 같은 파티션에 순서대로 전달됨 → 역전 가능성 최소화

## 기존 코드베이스 컨벤션

- 디렉토리 구조: `controller/ → service/ → repository/` + `kafka/` (Consumer) + `scheduler/`
- DTO: record 사용 (`PurchaseMessage`, `OrderStatusMessage` 등)
- 엔티티: `@Getter + @NoArgsConstructor(PROTECTED) + @Builder + 도메인 메서드`
- Redis 키: `goods:stock:{id}`, `user:purchase:{userId}:{goodsId}`, `saga:state:{traceId}`
- KafkaConsumerConfig: DLT는 `record.topic() + ".DLT"` 자동 라우팅

## 관련 파일/위치

**serverA:**
- `service/PromotionService.java` — reserveStock 성공 후 stock-snapshot produce 추가
- `service/SagaOrchestratorService.java` — handleSagaFailure 내 releaseStock 후 produce 추가
- `service/RedisStockService.java` — getCurrentStock 메서드 추가
- `dto/StockSnapshotMessage.java` — 신규

**serverB:**
- `kafka/StockSnapshotConsumer.java` — 신규
- `service/OrderQueryService.java` — traceId 기반 키 + stock 뷰 메서드
- `controller/OrderQueryController.java` — URL 변경 + 재고 API
- `dto/StockSnapshotMessage.java` — 신규

**인프라:**
- `docker-compose.yml` — redis-b 컨테이너 추가
- `serverB/application.yaml` — 로컬 Redis 포트 6380
