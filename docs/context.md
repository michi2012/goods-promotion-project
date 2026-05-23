# 맥락 노트: StockSnapshotBuffer — 로컬 배칭 후 Redis multiSet

## 왜 이 방식을 선택했는가
- `stock-snapshot` 토픽은 ServerA가 재고 선점마다 fire-and-forget으로 produce → 플래시 세일 시
  동일 goodsId에 대해 초당 수백~수천 개 메시지가 쏟아질 수 있음
- 기존 방식은 메시지 1개당 Redis SET 1회 → 네트워크 왕복이 메시지 수만큼 발생
- ServerB Redis는 읽기 전용 뷰(CQRS)이므로 중간 값은 의미 없고, 100ms 내 마지막 값만 중요
- 100ms 배칭 + `multiSet` 으로 Redis 왕복을 최대 1/N 으로 줄임 (N = 100ms 내 메시지 수)
- 사용자가 별도 버퍼 클래스 분리 방식(2번)을 명시 선택

## 검토했으나 채택하지 않은 대안

### 대안 A: HashMap + clear()
- 무엇: flush 시 `new HashMap<>(buffer)` 로 스냅샷 찍은 뒤 `buffer.clear()`
- 왜 안 썼나: clear()와 put() 사이 타이밍에 신규 값이 clear에 포함되어 유실될 수 있음

### 대안 B: AtomicReference<Map> 스왑
- 무엇: flush 시 빈 Map으로 atomic swap
- 왜 안 썼나: 정확하지만 코드가 복잡해짐. 조건부 remove로 충분히 안전

### 대안 C: Redis Pipeline (executePipelined)
- 무엇: `multiSet` 대신 파이프라인으로 여러 SET 묶기
- 왜 안 썼나: `multiSet`이 내부적으로 단일 MSET 명령을 사용하므로 동일 효과. 사용자가 multiSet을 명시.

## 기존 코드베이스 컨벤션
- 패키지 구조: `weverse.serverB.{kafka, service, config, dto, controller, exception}`
- 컨슈머는 `kafka/` 패키지에 위치 → `StockSnapshotBuffer`도 동일 패키지
- Redis 키 형식: `goods:view:stock:{goodsId}`, `order:view:{traceId}:status`
- `@Scheduled` 설정: `SchedulingConfig`에서 스레드풀(poolSize=3) 관리

## 관련 파일/위치
- `serverB/.../kafka/StockSnapshotConsumer.java` — Kafka 메시지 수신 (수정)
- `serverB/.../kafka/StockSnapshotBuffer.java` — 배칭 버퍼 (신규)
- `serverB/.../service/OrderQueryService.java` — Redis 쓰기 담당 (수정)
- `serverB/.../config/SchedulingConfig.java` — `@EnableScheduling` 설정 (변경 없음)
