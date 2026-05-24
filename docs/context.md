# 맥락 노트: 품절 로컬 캐시(Server A) + 재고 조회 read-through 캐시(Server B)

## 왜 이 방식을 선택했는가

### Server A 품절 캐시
플래시 세일에서 재고 소진 이후에도 수천 건의 요청이 Redis Lua 스크립트를 두드린다.
`reserveStock` Lua는 이미 원자적이고 빠르지만, 네트워크 왕복 자체가 누적 부하가 된다.
"이미 품절임을 알고 있는" 상태를 인메모리에 기억하면, 품절 이후 모든 요청을 Redis 0회로 처리할 수 있다.
`releaseStock` 시 즉시 무효화하여 보상 트랜잭션(Saga 실패 후 재고 복구) 정확성을 보장한다.

### Server B read-through 캐시
Server B Redis는 쓰기 경합이 없지만, 사용자 폴링으로 인한 읽기 폭발에 취약하다.
`getStockView()` 호출마다 Redis 왕복이 발생하므로, TTL 2초짜리 로컬 캐시로 읽기 부하를 대부분 차단한다.
`updateStockView()` 시 캐시도 동시 갱신하여 재고 변동이 즉시 반영되도록 한다.

## 검토했으나 채택하지 않은 대안

### Server A: 별도 SoldOutCacheService 분리
- 무엇: 품절 캐시를 독립 컴포넌트로 분리
- 왜 안 썼나: 재고 연산(reserveStock, releaseStock)과 캐시 조작이 항상 함께 발생하므로
  RedisStockService 내부에 두는 것이 응집도가 높고 호출부 수정을 최소화함

### Server A: ConcurrentHashMap 사용
- 무엇: Caffeine 대신 ConcurrentHashMap으로 품절 플래그 관리
- 왜 안 썼나: TTL 안전망이 없어 releaseStock 누락 시 영구 차단 위험.
  Caffeine TTL 60초로 방어선 추가

### Server B: Spring Cache 추상화(@Cacheable) 사용
- 무엇: @EnableCaching + @Cacheable 어노테이션으로 캐시 적용
- 왜 안 썼나: updateStockView에서 캐시를 직접 갱신해야 하는 요구사항 때문에
  직접 제어가 더 명확함. 추상화 레이어가 불필요한 복잡도 추가.

## 품절 캐시 무효화 경로
```
releaseStock 호출 위치 두 곳:
  1. PromotionService.rollbackRedis()   → Kafka 실패 시 재고 반환
  2. SagaOrchestratorService.handleSagaFailure() → Saga 보상 트랜잭션

두 곳 모두 RedisStockService.releaseStock()을 경유
→ 내부에서 soldOutCache.invalidate() 처리되므로 호출부 수정 불필요
```

## 관련 파일/위치
- `serverA/.../service/RedisStockService.java` — 품절 캐시 보관 및 조작
- `serverA/.../service/PromotionService.java` — 품절 사전 차단 (tryMarkUserPurchased 이전)
- `serverB/.../service/OrderQueryService.java` — read-through 캐시
