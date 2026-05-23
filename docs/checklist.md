# 체크리스트: StockSnapshotBuffer — 로컬 배칭 후 Redis multiSet

- 마지막 업데이트: 2026-05-23

## 진행 상황

- [ ] 단계 1: `OrderQueryService`에 `batchUpdateStockView` 추가
  - [ ] 검증: `gradlew.bat :serverB:compileJava`

- [ ] 단계 2: `StockSnapshotBuffer` 신규 생성
  - [ ] 검증: `gradlew.bat :serverB:compileJava`

- [ ] 단계 3: `StockSnapshotConsumer` 수정
  - [ ] 검증: `gradlew.bat :serverB:compileJava`

## 최종 검증
- [ ] `gradlew.bat :serverB:compileJava` 최종 통과
- [ ] 변경 사항이 비범위(ServerA 수정, 테스트 추가)를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경 없는지 git diff로 최종 확인

## 발견 사항
-
