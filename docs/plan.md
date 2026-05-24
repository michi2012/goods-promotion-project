# 계획서: 품절 로컬 캐시(Server A) + 재고 조회 read-through 캐시(Server B)

- 작성일: 2026-05-23

## 목표
Server A `PromotionService`에 품절 로컬 캐시를 추가해 이미 소진된 상품에 대한 Redis 왕복을 제거한다.
Server B `OrderQueryService`에 read-through 로컬 캐시를 추가해 재고 조회 API의 Redis 읽기 부하를 줄인다.
두 캐시 모두 Caffeine을 직접 사용한다 (의존성 이미 존재).

## 성공 기준
- [ ] 품절 상태에서 PromotionService가 Redis 호출 없이 SoldOutException을 던진다 (RedisStockService 메서드 호출 없음)
- [ ] releaseStock 호출 후 품절 캐시가 무효화되어 재고 복구가 정상 반영된다
- [ ] getStockView 최초 호출은 Redis에서 읽고, 2초 내 재호출은 로컬 캐시에서 반환한다
- [ ] updateStockView 호출 시 Redis와 로컬 캐시가 동시에 갱신된다
- [ ] `gradlew.bat :serverA:compileJava :serverB:compileJava` 통과

## 비범위
- build.gradle 의존성 추가 불필요 (Caffeine 이미 존재)
- SagaOrchestratorService 직접 수정 없음 (releaseStock 내부에서 처리)
- 품절 캐시 TTL 조정 설정화 (하드코딩 60초로 충분)
- Server B의 주문 상태(orderStatus) 캐싱 — 이번 작업 범위 아님

## 단계별 작업 계획

### 단계 1: Server A — RedisStockService에 품절 캐시 추가
- 변경 파일: `serverA/src/main/java/weverse/serverA/service/RedisStockService.java`
- 변경 내용:
  - Caffeine 캐시 필드 `Cache<Long, Boolean> soldOutCache` 추가 (TTL 60초)
  - `reserveStock()`: 반환값 false 시 `soldOutCache.put(goodsId, true)`
  - `releaseStock()`: `soldOutCache.invalidate(goodsId)`
  - 신규 메서드 `isKnownSoldOut(goodsId)`: 캐시 조회
- 검증: `gradlew.bat :serverA:compileJava`
- 롤백: `git restore serverA/src/main/java/weverse/serverA/service/RedisStockService.java`
- 예상 소요: 보통

### 단계 2: Server A — PromotionService에 품절 사전 차단 추가
- 변경 파일: `serverA/src/main/java/weverse/serverA/service/PromotionService.java`
- 변경 내용:
  - `acceptPurchase()` 최상단에 `isKnownSoldOut` 체크 추가
  - 품절 캐시 히트 시 Redis 왕복 없이 즉시 `SoldOutException` throw
  - 이 시점은 `tryMarkUserPurchased` 이전이므로 Redis 롤백 불필요
- 검증: `gradlew.bat :serverA:compileJava`
- 롤백: `git restore serverA/src/main/java/weverse/serverA/service/PromotionService.java`
- 예상 소요: 짧음

### 단계 3: Server B — OrderQueryService에 read-through 캐시 추가
- 변경 파일: `serverB/src/main/java/weverse/serverB/service/OrderQueryService.java`
- 변경 내용:
  - Caffeine 캐시 필드 `Cache<Long, Long> stockViewCache` 추가 (TTL 2초)
  - `getStockView()`: 캐시 조회 → 미스 시 Redis 조회 후 캐싱
  - `updateStockView()`: Redis SET과 동시에 캐시 갱신
- 검증: `gradlew.bat :serverB:compileJava`
- 롤백: `git restore serverB/src/main/java/weverse/serverB/service/OrderQueryService.java`
- 예상 소요: 보통

## 리스크 및 대응
- **멀티 인스턴스 시 품절 캐시 불일치**: 인스턴스 A가 releaseStock 해도 인스턴스 B의 캐시는 유지됨. TTL 60초 안전망으로 최대 60초 후 자정. 재고 복구 시나리오(Saga 실패)가 극히 드물어 허용 가능한 트레이드오프.
- **updateStockView Redis 실패 시 캐시 정합성**: Redis 쓰기 실패해도 캐시는 갱신됨. 다음 TTL(2초) 만료 후 Redis 재조회로 자정.

## 의존성
- `com.github.ben-manes.caffeine:caffeine` — 두 서버 모두 이미 존재
