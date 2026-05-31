# 체크리스트: Flyway 도입 + 초기 SQL 생성 + ddl-auto validate 전환

- 마지막 업데이트: 2026-05-31

## 진행 상황
- [x] 단계 1: Flyway 의존성 추가 (serverA/build.gradle, serverC/build.gradle)
  - [x] `.\gradlew.bat :serverA:build :serverC:build` 컴파일 성공
- [x] 단계 2: serverA 초기 SQL 작성 (V1__init_schema.sql)
  - [x] goods, orders, dead_letter, outbox_event 테이블 생성 구문 확인
- [x] 단계 3: serverC 초기 SQL 작성 (V1__init_schema.sql)
  - [x] payments, outbox_event 테이블 생성 구문 확인
- [x] 단계 4: ddl-auto validate 전환
  - [ ] serverA 앱 기동 후 `Successfully validated` 로그 확인 (사용자 직접)
  - [ ] serverC 앱 기동 후 `Successfully validated` 로그 확인 (사용자 직접)

## 최종 검증
- [x] `.\gradlew.bat :serverA:build :serverC:build` 성공
- [ ] serverA/serverC 앱 정상 기동 (Flyway + validate 통과) — 사용자 직접 확인
- [x] serverB 무변경 확인
- [x] 변경 범위가 plan.md 비범위를 침범하지 않았는지 확인

## 발견 사항
- 없음
