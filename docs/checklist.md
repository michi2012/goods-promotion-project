# 체크리스트: 품절 로컬 캐시(Server A) + 재고 조회 read-through 캐시(Server B)

- 마지막 업데이트: 2026-05-23

## 진행 상황

- [ ] 단계 1: Server A — RedisStockService 품절 캐시 추가
  - [ ] soldOutCache 필드 (Caffeine, TTL 60초)
  - [ ] reserveStock(): false 반환 시 markSoldOut
  - [ ] releaseStock(): invalidate
  - [ ] isKnownSoldOut() 메서드 신규
  - [ ] 검증: `gradlew.bat :serverA:compileJava`

- [ ] 단계 2: Server A — PromotionService 품절 사전 차단
  - [ ] acceptPurchase() 최상단에 isKnownSoldOut 체크 추가
  - [ ] 검증: `gradlew.bat :serverA:compileJava`

- [ ] 단계 3: Server B — OrderQueryService read-through 캐시
  - [ ] stockViewCache 필드 (Caffeine, TTL 2초)
  - [ ] getStockView(): 캐시 → Redis 순서
  - [ ] updateStockView(): Redis + 캐시 동시 갱신
  - [ ] 검증: `gradlew.bat :serverB:compileJava`

## 최종 검증
- [ ] `gradlew.bat :serverA:compileJava :serverB:compileJava` 최종 통과
- [ ] SagaOrchestratorService 미수정 확인 (git diff)
- [ ] build.gradle 미수정 확인 (의존성 추가 없음)

## 발견 사항
-
