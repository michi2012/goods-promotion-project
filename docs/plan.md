# 계획서: reserveStock -1/-2 분기 처리

- 작성일: 2026-05-31

## 목표
`reserveStock()` Lua 결과 -1(재고 부족)과 -2(Redis 키 없음)를 `acceptPurchase()`에서 구분 처리한다.
-1은 기존 SoldOutException(400), -2는 RuntimeException(500 내부 오류)으로 응답하고, soldOutCache는 -1 시에만 등록한다.

## 성공 기준
- [ ] reserveStock()이 Long을 반환하고, soldOutCache 등록이 result == -1L일 때만 발생한다.
- [ ] acceptPurchase()가 -1이면 SoldOutException, -2이면 RuntimeException을 throw한다.
- [ ] -2 케이스에서 userPurchase 플래그가 롤백된다.
- [ ] 기존 테스트가 모두 통과하고, 새 -2 케이스 테스트가 추가된다.
- [ ] gradlew :serverA:test 전체 통과

## 비범위 (Out of Scope)
- GlobalExceptionHandler 수정 없음 (RuntimeException → 500은 기존 handleGeneralError가 이미 처리)
- 새 예외 클래스 추가 없음
- isKnownSoldOut() / Caffeine 캐시 TTL 변경 없음

## 단계별 작업 계획

### 단계 1: RedisStockService.reserveStock() 반환 타입 변경
- 변경 파일: serverA/src/main/java/weverse/serverA/service/RedisStockService.java
- 변경 내용: boolean → Long 반환. soldOutCache 등록 조건을 result == -1L 시에만 실행.
- 검증 방법: PromotionService 컴파일 오류 확인 (reserveStock 호출부 타입 불일치 예상)
- 예상 소요: 짧음

### 단계 2: PromotionService.acceptPurchase() 분기 처리
- 변경 파일: serverA/src/main/java/weverse/serverA/service/PromotionService.java
- 변경 내용: reserveStock() 반환 Long으로 받아서 분기.
  - result >= 0 → 정상
  - result == -1L → releaseUserPurchase + throw SoldOutException
  - result == -2L → releaseUserPurchase + log.error + throw RuntimeException("재고 미초기화")
- 검증 방법: 컴파일 통과 확인
- 예상 소요: 짧음

### 단계 3: 테스트 업데이트
- 변경 파일: serverA/src/test/java/weverse/serverA/service/PromotionServiceTest.java
- 변경 내용:
  - 기존 SoldOut 테스트: willReturn(false) → willReturn(-1L)
  - 기존 Success 테스트: willReturn(true) → willReturn(5L)
  - 새 테스트 추가: reserveStock이 -2L 반환 시 RuntimeException throw + userPurchase 롤백 확인
- 검증 방법: gradlew :serverA:test
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: RedisStockServiceTest가 reserveStock 반환 타입 변경 영향 받을 수 있음
- 대응: 단계 3에서 RedisStockServiceTest도 함께 확인
