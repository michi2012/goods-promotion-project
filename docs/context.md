# 맥락 노트: PG 대사 PR③ — 시드 생성기 + 기준선 측정 (BL-047)

## [완료] BL-002 PR③ — BL-047 (PR #65)

## 왜 이 방식을 선택했는가
- 명세가 "시드 생성기는 `payments` 행과 CSV를 직접 만든다(원장 경유 X)"로 확정. 대량 결제를 실제 결제 흐름으로 만들면 측정 대상이 아닌 Saga·PG 지연이 섞인다.
- **Node 스크립트(의존성 없음)** — 사용자 결정. 로컬·CI 공유 스크립트는 셸이 아니라 프로젝트 런타임으로 작성한다는 규칙(`.claude/skills/e2e`)에 맞고 새 의존성이 없다. DB 드라이버 없이 SQL 파일을 만들어 `docker cp` + `mysql <`로 적용(로컬 PowerShell 따옴표 함정 회피).
- **규모 10만 + 100만** — 사용자 결정. 단일 판매자 쇼핑몰의 현실 상한과 10배 여유. 두 점으로 선형성 확인.
- **배치 단독 측정 + 재시작 절약 시간** — 사용자 결정. 실시간 간섭은 BL-008 판단 시 필요하면 별도.
- 반복은 같은 날짜 재실행(COMPLETED → `requestedAt` 새 실행, Step 1이 이전 결과 삭제)으로 한다. 시드 한 번으로 같은 데이터 반복 측정 가능.
- 재시작은 `docker kill`로 실제 장애를 흉내낸다 — 기동 시 정리(`ReconciliationStartupCleaner`)가 STARTED를 FAILED로 바꾸고, API가 RESTART로 실패 Step의 마지막 커밋부터 잇는 실제 운영 경로 그대로.

## 검토했으나 채택하지 않은 대안
### serverC 테스트 쪽 Java 생성기
- 무엇: JdbcTemplate로 mysql-c에 바로 batch insert
- 왜 안 썼나: 테스트 소스에 운영용 도구가 섞이고, CSV를 컨테이너에 넣는 단계는 어차피 별도

### SQL 재귀 CTE로 `payments` 생성
- 무엇: DB 안에서 행 생성, CSV는 따로
- 왜 안 썼나: 불일치 유형을 심으려면 양쪽이 같은 규칙을 공유해야 해 생성 로직이 두 곳으로 갈라짐

## 기존 코드베이스 컨벤션
- k6 스크립트 `perf/k6/`, 결과 `docs/perf/YYYY-MM-DD-{주제}.md` (`.claude/skills/perf-compare/project.md`)
- 배치 지표 출처: `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` (Spring Batch 5, V7)
- CSV 형식: `order_id,event_type,amount,occurred_at` 헤더 1줄 (`MockPgFileExporter`)

## 관련 파일/위치
- `serverC/src/main/java/promotion/serverC/batch/ReconciliationJobConfig.java` — Step 구성(clear, prevTail, loadDay, ours→PG 3a·3b, PG→ours, report), chunk 500
- `serverC/src/main/resources/application.yaml` — `mock-pg.file-dir`(`./pg-files` → 컨테이너 `/app/pg-files`), `reconciliation.chunk-size`
- `serverC/src/main/resources/db/migration/V1~V7` — `payments`(amount·approved_at·cancelled_at), 대사 테이블
- 관리자 API `POST /api/v1/payments/admin/reconciliations?date=` (`X-User-Role` 관리자)

## 외부 참조
- 명세 `docs/specs/pg-reconciliation-batch.md`, perf-compare `SKILL.md`
