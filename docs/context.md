# 맥락 노트: PG 대사 PR②-a — 대사 Job 본체 (BL-050)

## [완료] BL-002 PR②-a — BL-050 (PR #63)

## 왜 이 방식을 선택했는가
- 요구사항·설계는 명세(`docs/specs/pg-reconciliation-batch.md`, 설계 확인 ☑)가 전제. 여기에는 /plan에서 정한 구현 세부만 적는다.
- **PR 분할 (2026-11-17 사용자 결정)**: 명세 PR②(BL-046)를 ②-a Job 본체(BL-050)와 ②-b 운영 연결(BL-051: 수동 실행 API·스케줄·기동 시 정리·메트릭·알람)로 나눴다. 예상 변경 파일 22~25개로 리뷰 기준(약 20개)을 넘고, 위험 변경(Batch 도입·마이그레이션·정합성)과 운영 연결을 분리하면 리뷰가 선명해진다.
- **chunk 크기 500** (설정값 `reconciliation.chunk-size`, page size 동일): Spring Batch에는 기본값이 없어 정해야 했다. 튜닝은 BL-047 측정 후 BL-008.
- **D-1 파일 없음 → 경고 후 진행**: D-1 파일은 날짜 경계 확인(P-5)용일 뿐이고, 마이그레이션 직후 첫날엔 원래 없다. D 파일 없음만 실패(S-10).
- **Step 3을 3a(승인)·3b(취소)로 분리**: 키셋 정렬 키가 `(approved_at, id)`·`(cancelled_at, id)`로 달라 한 reader로 묶을 수 없다.
- **S-11 재현 설계**: 지난 날짜의 이벤트 시각은 바뀌지 않아 이벤트 시각 조건이면 OFFSET이어도 누락이 안 난다. 단순 구현은 "상태 조건 + OFFSET"(`status='PAID'` 등)으로 두고 페이지 사이에 앞쪽 행을 취소해 밀림 누락을 재현, Job(이벤트 시각 + 키셋)은 누락 없음을 보인다.
- **경계 보정 판정**: Step 3 "우리만" 후보는 같은 `recon_date`로 적재된 D-1 꼬리 줄에서 `우리 시각 - PG 시각 ≤ 5분`이면 제외. Step 4 "PG만/상태" 후보는 `payments`에서 같은 이벤트 시각이 `[PG 시각, PG 시각 + 5분]`이면 제외. Step 4는 `occurred_at`이 D 안인 줄만 읽는다(D-1 꼬리는 비교 대상 아님).
- **경계 쌍은 금액도 비교하지 않는다 (구현 중 결정)**: 경계 쌍에서 금액을 비교하면 D·D+1 양쪽 대사가 같은 쌍을 각자 ③으로 기록해 이중 보고된다. 명세 P-5 문구대로 "불일치에서 제외"를 양쪽 모두 적용. 금액 비교는 같은 날 안에서 짝지어진 이벤트에만 하고, Step 4는 존재 여부만 본다(금액은 Step 3 담당 — 같은 쌍을 두 번 기록하지 않게).

## 검토했으나 채택하지 않은 대안
### 한 PR로 진행 (명세 원안)
- 왜 안 썼나: 파일 수 초과, 리뷰 초점 분산.
### Step 3을 UNION 쿼리 하나로
- 왜 안 썼나: `JdbcPagingItemReader` 키셋은 단일 테이블 정렬 키 전제. UNION 서브쿼리는 키셋 조건이 인덱스를 못 탄다.

## 기존 코드베이스 컨벤션
- 패키지: 계층형(`controller/service/repository/scheduler`). 배치 전용 클래스는 `promotion.serverC.batch`에 모은다(Job 구성·reader·processor·writer).
- 저장소: JdbcTemplate 직접 사용 (`repository/PaymentRepository.java`)
- 시각: `ReconciliationTime.ZONE`/`now()` (KST 고정)
- 테스트: Testcontainers 싱글턴 `support/AbstractRepositoryTest`, 커밋이 필요한 흐름은 `@DataJpaTest` + `@Transactional(NOT_SUPPORTED)` + `@Import` (`service/PaymentCancelRetryIntegrationTest.java`)

## 관련 파일/위치
- `serverC/src/main/java/promotion/serverC/scheduler/MockPgFileExporter.java` — CSV 형식·파일명(`pg-transactions-YYYYMMDD.csv`), `mock-pg.file-dir`
- `serverC/src/main/java/promotion/serverC/repository/PaymentRepository.java` — `approved_at`/`cancelled_at` 기록
- `serverC/src/main/resources/db/migration/` — V1~V5, 이번 V6·V7

## 외부 참조
- Spring Batch 5 `schema-mysql.sql` (spring-batch-core jar `org/springframework/batch/core/schema-mysql.sql`)
