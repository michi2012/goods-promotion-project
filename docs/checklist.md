# 체크리스트: 결제 메트릭 에러 타입 레이블 분리

- 마지막 업데이트: 2026-05-26

## 진행 상황
- [x] 단계 1: MockPgClient.java fallback → PgPaymentException throw
  - [x] 검증 통과 (`.\gradlew.bat :serverC:compileJava` BUILD SUCCESSFUL)
- [x] 단계 2: PaymentService.java 타입 레이블 카운터 교체
  - [x] 검증 통과 (`.\gradlew.bat :serverC:compileJava` BUILD SUCCESSFUL)
- [x] 단계 3: alert-rules.yml type 필터 추가
  - [x] 검증 통과 (type="pg_system_error" 2개 룰 적용 확인)

## 최종 검증
- [ ] 모든 단계 검증 통과
- [ ] plan.md 비범위 침범 없음 확인
- [ ] `git diff --stat` 변경 파일 확인

## 발견 사항
-
