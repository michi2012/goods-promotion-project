# 체크리스트: reserveStock -1/-2 분기 처리

- 마지막 업데이트: 2026-05-31

## 진행 상황
- [x] 단계 1: RedisStockService.reserveStock() 반환 타입 Long으로 변경
  - [x] soldOutCache 등록이 -1L 시에만 동작 확인
- [x] 단계 2: PromotionService.acceptPurchase() 분기 처리
  - [x] -1L → SoldOutException 확인
  - [x] -2L → RuntimeException + userPurchase 롤백 확인
- [x] 단계 3: PromotionServiceTest 업데이트 및 새 케이스 추가
  - [x] RedisStockServiceTest도 Long 반환 타입에 맞게 수정 + 키 없음 케이스 추가
  - [ ] 검증 통과 (gradlew :serverA:test)

## 최종 검증
- [ ] 모든 단위 테스트 통과
- [ ] 변경 사항이 plan.md의 비범위를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항
- RedisStockServiceTest도 reserveStock 반환 타입 변경 영향 → 함께 수정함
