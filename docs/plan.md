# 계획서: PG 대사 PR②-a — 대사 Job 본체 (BL-050)

## [완료] BL-002 PR②-a — BL-050 (PR #63)

- 작성일: 2026-11-17
- 관련 이슈/티켓: BL-050 (BL-046의 하위, BL-002 묶음, `docs/backlog.md`), 명세 `docs/specs/pg-reconciliation-batch.md` (설계 확인 ☑)

## 목표
serverC에 Spring Batch `pgReconciliationJob`(Step 1~5)을 도입해, 지정한 대사일의 우리 결제 이벤트와 PG 거래 파일을 비교하고 4가지 불일치를 `reconciliation_mismatches`에 기록한다. 정합성 요건 3종(컷오프·재시작·키셋)을 단순 구현과 비교하는 재현 테스트로 증명한다.

## 성공 기준
- [ ] S-1, S-3~S-7 시드로 일치·4가지 불일치·날짜 경계 제외가 판정된다 (`ReconciliationJobTest`, Testcontainers MySQL)
- [ ] S-10: D 파일이 없으면 Job FAILED, D-1 파일이 없으면 경고 후 진행 (`ReconciliationJobTest`)
- [ ] 재현 ① S-2·S-8 컷오프: 현재 상태 비교 단순 구현은 실패, Job은 재실행해도 결과 동일·한 벌 (`ReconciliationCutoffReproTest`)
- [ ] 재현 ② S-9 재시작: 한 트랜잭션 단순 구현은 전체 롤백, Job은 N번째 chunk 실패 후 같은 파라미터 재실행 시 실패 chunk부터 이어 최종 결과가 한 번에 성공한 것과 같다 (`ReconciliationRestartReproTest`)
- [ ] 재현 ③ S-11 누락: 상태 조건 + OFFSET 단순 구현은 처리 중 취소로 누락, Job(이벤트 시각 + 키셋)은 누락 없음 (`ReconciliationPagingReproTest`)
- [ ] `gradlew :serverC:test` 전체 통과, 로컬 compose serverC 기동 시 V6·V7 적용 + 컨텍스트 정상 기동

## 비범위 (Out of Scope)
- 수동 실행 API, `@Scheduled` 03:00 실행, 기동 시 STARTED 실행 정리, 메트릭·알람 → **BL-051 (PR②-b)**
- 시드 생성기·기준선 측정 → BL-047
- chunk 크기 튜닝·커넥션 풀 분리 → BL-008 (조건부)
- 다중 파드 파일 저장소 → BL-048
- 불일치 조회·해결 API, 자동 보정 (명세 범위 밖)

## 단계별 작업 계획

### 단계 1: 의존성·설정·마이그레이션
- 변경 파일: `serverC/build.gradle`, `application.yaml`, `db/migration/V6__reconciliation_tables.sql`, `V7__spring_batch_schema.sql`
- 변경 내용: `spring-boot-starter-batch`·`spring-batch-test`(승인됨). `spring.batch.job.enabled: false`, `spring.batch.jdbc.initialize-schema: never`, `reconciliation.chunk-size: 500`, `reconciliation.boundary-tolerance: 5m`. V6 = `pg_settlement_lines`·`reconciliation_mismatches`(명세 DDL), V7 = Spring Batch 공식 `schema-mysql.sql`(사용 중인 spring-batch-core 버전 jar에서 복사).
- 검증: `gradlew :serverC:compileJava`, 기존 Testcontainers 테스트 1개(`PaymentRepositoryTest`)로 Flyway V6·V7 적용 확인
- 롤백: 파일 되돌리기 (DB 미적용 상태)
- 예상 소요: 짧음

### 단계 2: 대사 저장소 (JDBC)
- 변경 파일: `repository/PgSettlementLineRepository`, `repository/ReconciliationMismatchRepository`, `PaymentRepository`(이벤트 조회 메서드 추가), 각 테스트
- 변경 내용: PG 줄 `INSERT IGNORE`·키 조회·날짜 삭제, 불일치 `INSERT IGNORE`·날짜 삭제·조회, 결제 건 존재·이벤트 시각 조회.
- 검증: `gradlew :serverC:test --tests "*PgSettlementLineRepositoryTest" --tests "*ReconciliationMismatchRepositoryTest" --tests "*PaymentRepositoryTest"`
- 롤백: 새 파일 삭제, `PaymentRepository` 되돌리기
- 예상 소요: 보통

### 단계 3: Job 구성 — Step 1 clear, Step 2 load
- 변경 파일: `batch/ReconciliationJobConfig`, `batch/PgFileLineReader`(D 파일 + D-1 파일 마지막 5분), `batch/ReconciliationJobTest`
- 변경 내용: `reconDate` 파라미터, clear tasklet, `FlatFileItemReader` chunk 적재. D 파일 없음 → Step 실패, D-1 파일 없음 → 경고 로그 후 D 파일만.
- 검증: `gradlew :serverC:test --tests "*ReconciliationJobTest"` (적재·S-10)
- 롤백: 새 파일 삭제
- 예상 소요: 보통

### 단계 4: Step 3a·3b ours→PG, Step 4 PG→ours, Step 5 요약
- 변경 파일: `ReconciliationJobConfig`, `batch/OursToPgProcessor`, `batch/PgToOursProcessor`, `batch/MismatchWriter`, `batch/ReconciliationJobTest`
- 변경 내용: 3a 승인(`approved_at, id`)·3b 취소(`cancelled_at, id`) `JdbcPagingItemReader` 키셋 → ①③, 4는 D 범위 PG 줄 키셋 → ②④. 경계 보정(P-5, 허용 5분, "PG 시각 ≤ 우리 시각"). Step 5는 유형별 건수 로그(메트릭은 BL-051).
- 검증: `ReconciliationJobTest` S-1, S-3~S-7
- 롤백: 단계 3 상태로 되돌리기
- 예상 소요: 김

### 단계 5: 재현 테스트 3종
- 변경 파일: `batch/ReconciliationCutoffReproTest`, `ReconciliationRestartReproTest`, `ReconciliationPagingReproTest` (단순 구현은 테스트 안에만)
- 변경 내용: 성공 기준의 재현 ①②③. 재시작은 불일치 저장소 spy로 N번째 chunk에서 예외 → FAILED → 예외 해제 후 같은 파라미터로 재실행.
- 검증: `gradlew :serverC:test --tests "*ReproTest"`
- 롤백: 테스트 파일 삭제
- 예상 소요: 보통

### 단계 6: 전체 검증·문서
- 변경 파일: `docs/checklist.md`, `docs/backlog.md`, (필요 시) `.claude/skills/*/project.md`
- 변경 내용: 전체 테스트, 로컬 serverC 재기동(V6·V7 적용 — **기동 전 사용자 확인**), 발견 사항 백로그 이전.
- 검증: `gradlew :serverC:test` (background), serverC 기동 로그·`flyway_schema_history`
- 롤백: 해당 없음
- 예상 소요: 보통

## 리스크 및 대응
- Boot 자동 구성 Batch + JPA 트랜잭션 매니저 공존 → `@EnableBatchProcessing` 쓰지 않음(쓰면 Boot 자동 구성이 꺼짐). JdbcTemplate는 같은 DataSource라 chunk 트랜잭션에 참여.
- 슬라이스 테스트에서 Batch 컨텍스트 구성 → `@DataJpaTest` + `@ImportAutoConfiguration(BatchAutoConfiguration)` + Job 구성 Import 방식(기존 `PaymentCancelRetryIntegrationTest` 패턴). 안 되면 정지 후 보고.
- `JdbcPagingItemReader`가 MySQL에서 정렬 키 2개 키셋 조건을 만드는지 → 단계 4에서 생성 SQL 확인, 재현 ③이 증명.
- PR②-a만으로는 Job을 띄울 진입점(API·스케줄)이 없음 → E2E(API 호출)는 BL-051에서. 이 PR은 Testcontainers Job 테스트 + 서버 기동 확인으로 검증.

## 의존성
- BL-045(머지됨): `payments.approved_at/cancelled_at`, `MockPgFileExporter` CSV 형식, `ReconciliationTime.ZONE`
- Docker(Testcontainers MySQL), 로컬 compose `promotion-mysql-c`·`promotion-server-c`
