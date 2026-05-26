# 체크리스트: PromotionException log.error 전환 + 에러 테스트 컨트롤러 추가

- 마지막 업데이트: 2026-05-25

## 진행 상황
- [x] 단계 1: GlobalExceptionHandler log.error 변경
  - [x] 파일 내용 확인
- [x] 단계 2: ErrorTestController 생성
  - [x] 빌드 통과 (`gradlew.bat :serverA:compileJava`)

## 최종 검증
- [x] 빌드 통과
- [x] 변경 사항이 비범위를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경 없는지 git diff로 확인
