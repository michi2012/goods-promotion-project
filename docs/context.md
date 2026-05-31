# 맥락 노트: Flyway 도입 + 초기 SQL 생성 + ddl-auto validate 전환

## 왜 이 방식을 선택했는가
`ddl-auto: create`는 앱 재시작마다 테이블을 드롭/재생성하므로 운영 환경에서 사용 불가.
Flyway를 도입해 마이그레이션 이력을 관리하고, validate로 Hibernate가 스키마를 신뢰하되 직접 조작하지 않도록 한다.
초기 SQL은 엔티티 분석으로 수작업 작성 — Hibernate schema generation을 쓰지 않은 이유는 생성 SQL이 프로덕션 수준의 DDL(인덱스 이름, 문자셋 등)을 보장하지 않기 때문.

## 검토했으나 채택하지 않은 대안

### 대안 A: ddl-auto: update
- 무엇: Hibernate가 엔티티 변경을 감지해 스키마 자동 수정
- 왜 안 썼나: 컬럼 삭제 시 DROP이 발생하지 않아 스키마 드리프트 축적. 운영 환경 비권장.

### 대안 B: ddl-auto: none
- 무엇: Hibernate가 스키마에 전혀 관여하지 않음
- 왜 안 썼나: 스키마 불일치를 런타임에야 발견. validate가 시작 시점에 즉시 감지해 더 안전.

### 대안 C: Hibernate schema generation으로 SQL 자동 추출
- 무엇: `jakarta.persistence.schema-generation.scripts.action=create` 로 DDL 파일 출력
- 왜 안 썼나: 인덱스 이름, 문자셋, 컬럼 순서 등이 엔티티 선언 순서에 종속됨. 직접 작성한 SQL이 의도를 더 명확히 표현.

## 적용 범위
- serverA: MySQL (port 3306, DB: promotion) — 엔티티 4개 (goods, orders, dead_letter, outbox_event)
- serverB: JPA 없음 → Flyway 불필요
- serverC: MySQL (port 3308, DB: payment) — 엔티티 2개 (payments, outbox_event)

## 관련 파일/위치
- `serverA/build.gradle` — flyway 의존성
- `serverC/build.gradle` — flyway 의존성
- `serverA/src/main/resources/db/migration/V1__init_schema.sql` — 초기 스키마
- `serverC/src/main/resources/db/migration/V1__init_schema.sql` — 초기 스키마
- `serverA/src/main/resources/application.yaml` — ddl-auto 변경
- `serverC/src/main/resources/application.yaml` — ddl-auto 변경

## Docker 환경 주의사항
컨테이너 볼륨이 살아있으면 기존 테이블이 남아있고 flyway_schema_history가 없어 Flyway가 오류를 낼 수 있음.
`docker compose down -v` 로 볼륨까지 제거 후 재시작하면 항상 fresh하게 동작.
