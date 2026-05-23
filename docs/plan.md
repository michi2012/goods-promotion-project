# 계획서: StockSnapshotBuffer — 로컬 배칭 후 Redis multiSet

- 작성일: 2026-05-23

## 목표
ServerB의 `StockSnapshotConsumer`가 Kafka 메시지를 받는 즉시 Redis에 쓰는 대신,
`StockSnapshotBuffer`에 goodsId별 최신 재고를 모아 두었다가 100ms마다 `multiSet`으로 일괄 플러시한다.
Redis 네트워크 왕복을 최소화하여 플래시 세일 고부하 상황에서의 Redis 쓰기 압력을 줄인다.

## 성공 기준
- [ ] `StockSnapshotBuffer.flush()` 호출 시 버퍼가 비어 있으면 Redis 호출을 하지 않는다
- [ ] 동일 goodsId에 대해 flush 전 여러 값이 들어오면, 마지막 값만 Redis에 쓰인다
- [ ] flush와 consume이 동시에 실행될 때 신규 put이 유실되지 않는다 (조건부 remove)
- [ ] `gradlew.bat :serverB:compileJava` 통과

## 비범위
- 테스트 추가 (사용자 미요청)
- `SchedulingConfig` 수정 (이미 `@EnableScheduling` + 풀 사이즈 3 설정됨)
- ServerA 측 변경 없음

## 단계별 작업 계획

### 단계 1: `OrderQueryService`에 `batchUpdateStockView` 추가
- 변경 파일: `serverB/src/main/java/weverse/serverB/service/OrderQueryService.java`
- 변경 내용: `Map<Long, Long>` 을 받아 key를 `goods:view:stock:{goodsId}` 형태로 변환 후
  `StringRedisTemplate.opsForValue().multiSet()` 으로 일괄 저장하는 메서드 추가
- 검증: `gradlew.bat :serverB:compileJava`
- 롤백: `git restore serverB/src/main/java/weverse/serverB/service/OrderQueryService.java`
- 예상 소요: 짧음

### 단계 2: `StockSnapshotBuffer` 신규 생성
- 변경 파일: 신규 `serverB/src/main/java/weverse/serverB/kafka/StockSnapshotBuffer.java`
- 변경 내용:
  - `ConcurrentHashMap<Long, Long> buffer` 로 goodsId → remainingStock 보관
  - `put(goodsId, stock)`: 항상 최신값으로 덮어쓰기
  - `flush()`: 버퍼가 비어 있으면 스킵. 아니면 entrySet을 순회하며
    `buffer.remove(goodsId, stock)` 조건부 제거 후 `batchUpdateStockView` 호출
  - `@Scheduled(fixedRate = 100)` 으로 flush 주기 설정
- 검증: `gradlew.bat :serverB:compileJava`
- 롤백: 파일 삭제
- 예상 소요: 보통

### 단계 3: `StockSnapshotConsumer` 수정
- 변경 파일: `serverB/src/main/java/weverse/serverB/kafka/StockSnapshotConsumer.java`
- 변경 내용: `OrderQueryService` 직접 호출 제거 → `StockSnapshotBuffer.put()` 호출로 교체
- 검증: `gradlew.bat :serverB:compileJava`
- 롤백: `git restore serverB/src/main/java/weverse/serverB/kafka/StockSnapshotConsumer.java`
- 예상 소요: 짧음

## 리스크 및 대응
- **flush 도중 신규 put 유실**: `buffer.remove(goodsId, stock)` 조건부 제거로 대응 — 값이 바뀐 경우 remove가 실패하므로 다음 주기에 처리됨
- **버퍼가 비어 있을 때 Redis 호출**: `buffer.isEmpty()` 체크로 스킵

## 의존성
- `StringRedisTemplate` — `OrderQueryService`에 이미 주입됨
- `@EnableScheduling` — `SchedulingConfig`에 이미 설정됨
