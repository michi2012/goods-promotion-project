# 맥락 노트: reserveStock -1/-2 분기 처리

## 왜 이 방식을 선택했는가
Lua 스크립트는 이미 -2(키 없음)와 -1(재고 부족)을 구분해 반환하고 있었으나,
Java에서 boolean으로 압축하면서 두 케이스가 동일하게 처리됐다.
reserveStock() 반환 타입을 Long으로 바꿔 Lua 결과를 그대로 올리고,
PromotionService에서 분기 처리하는 것이 가장 단순하고 추가 추상화 없이 해결된다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 새 예외 클래스 StockNotInitializedException 추가
- 무엇: BusinessException 하위 클래스로 별도 예외 추가
- 왜 안 썼나: 기존 handleGeneralError(Exception → 500)가 이미 RuntimeException을 처리하므로
  새 클래스 없이도 동일한 HTTP 응답을 낼 수 있다. 불필요한 추상화.

### 대안 B: reserveStockRaw() 메서드 별도 추가
- 무엇: 기존 reserveStock(boolean)은 유지하고 Long 반환 메서드 추가
- 왜 안 썼나: 두 메서드가 동일한 Lua 스크립트를 호출하는 중복. 기존 메서드를 직접 변경하는 게 단순하다.

## 기존 코드베이스 컨벤션
- 예외 계층: BusinessException(400) / PromotionException / RuntimeException(500 via handleGeneralError)
- 예외 파일 위치: serverA/src/main/java/promotion/serverA/exception/
- 테스트 구조: @ExtendWith(MockitoExtension.class), BDDMockito 스타일
- 테스트 파일: serverA/src/test/java/promotion/serverA/service/PromotionServiceTest.java

## 관련 파일/위치
- RedisStockService.java — reserveStock() Lua 실행 및 soldOutCache 관리
- PromotionService.java — acceptPurchase() 구매 플로우
- PromotionServiceTest.java — 단위 테스트 (Mockito)
