# 계획서: Flyway 도입 + 초기 SQL 생성 + ddl-auto validate 전환

- 작성일: 2026-05-31

## 목표
serverA/serverC에 Flyway를 도입하고, 기존 엔티티를 기반으로 초기 SQL 마이그레이션 파일을 생성한다.
`ddl-auto: create`를 `validate`로 교체해 Hibernate가 스키마를 관리하지 않도록 한다.

## 성공 기준
- [ ] `.\gradlew.bat :serverA:build :serverC:build` 컴파일 성공
- [ ] serverA 앱 시작 시 Flyway V1 실행 후 Hibernate validate 통과 (로그 확인)
- [ ] serverC 앱 시작 시 Flyway V1 실행 후 Hibernate validate 통과 (로그 확인)
- [ ] serverB는 무변경

## 비범위 (Out of Scope)
- serverB (JPA/DB 없음)
- mcp 모듈
- Flyway Undo(롤백) 스크립트 작성
- 테스트 전용 설정 변경 (Testcontainers가 fresh DB → Flyway 자동 실행)
- flyway.cleanDisabled 변경

## 단계별 작업 계획

### 단계 1: Flyway 의존성 추가
- 변경 파일: `serverA/build.gradle`, `serverC/build.gradle`
- 변경 내용: `flyway-core`, `flyway-mysql` 의존성 추가
- 검증 방법: `.\gradlew.bat :serverA:build :serverC:build`
- 롤백 방법: 의존성 두 줄 제거
- 예상 소요: 짧음

### 단계 2: serverA 초기 SQL 작성
- 변경 파일: `serverA/src/main/resources/db/migration/V1__init_schema.sql` (신규)
- 변경 내용: goods, orders, dead_letter, outbox_event 테이블 + 인덱스
- 검증 방법: 엔티티 필드와 SQL 컬럼 육안 대조
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 3: serverC 초기 SQL 작성
- 변경 파일: `serverC/src/main/resources/db/migration/V1__init_schema.sql` (신규)
- 변경 내용: payments, outbox_event 테이블 + unique constraint
- 검증 방법: 엔티티 필드와 SQL 컬럼 육안 대조
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 4: ddl-auto validate 전환
- 변경 파일: `serverA/src/main/resources/application.yaml`, `serverC/src/main/resources/application.yaml`
- 변경 내용: `ddl-auto: create` → `ddl-auto: validate`
- 검증 방법: 앱 기동 로그 `Successfully validated N schema objects`
- 롤백 방법: `ddl-auto: create`로 되돌리기
- 예상 소요: 짧음

## 리스크 및 대응
- SQL 타입이 Hibernate 기대값과 불일치 → validate 실패
  대응: 로그의 `Schema-validation: missing column` 메시지 확인 후 SQL 수정
- 기존 Docker 컨테이너에 이미 `create`로 만들어진 테이블 + flyway_schema_history 없음
  대응: `docker compose down -v` 후 재시작으로 fresh DB에서 시작

## 의존성
- 앱 기동 검증은 MySQL 컨테이너 실행 중이어야 가능
