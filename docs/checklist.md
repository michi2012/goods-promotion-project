# 체크리스트: OutboxProcessor 중복 SELECT 제거

- 마지막 업데이트: 2026-05-21 (완료)

## 진행 상황
- [x] 단계 1: OutboxProcessor 시그니처 변경
  - [x] 검증 통과 (`gradlew.bat :serverA:compileJava`)
- [x] 단계 2: PromotionService 호출부 변경
  - [x] 검증 통과 (`gradlew.bat :serverA:compileJava`)
- [x] 단계 3: 테스트 수정 및 전체 통과
  - [x] 검증 통과 (`gradlew.bat :serverA:test` BUILD SUCCESSFUL)

## 최종 검증
- [x] `gradlew.bat :serverA:test` 전체 통과
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경 없음

## 발견 사항
-
