# PG 거래 내역 대사 배치

- 요구사항 확인 ☑ (2026-11-17)
- 설계 확인 ☑ (2026-11-17)
- 작업 항목: BL-002 (PR별 하위: BL-045 → BL-046 → BL-047)

## 1. 요구사항

PG사가 날짜별로 주는 거래 내역과 우리 결제 기록(serverC `payments`)을 하루 단위로 비교해, 돈의 흐름이 어긋난 건을 찾아 기록하고 알린다. 실시간 보정(serverA 재대사 스케줄러)이 놓친 건을 잡는 **마지막 감시선**이다.

### 정책
| ID | 정책 |
|----|------|
| P-1 | 대사 단위는 **하루(대사일 D 00:00 ~ D+1 00:00, KST)**. 매일 새벽에 전일(D-1)분을 자동 실행한다 |
| P-2 | PG 거래 내역은 **일자별 CSV 파일**로 받는다. 파일 한 줄 = 거래 이벤트 1건(승인 또는 취소, orderId, 금액, 발생 시각). PG 거절 건은 돈이 움직이지 않아 파일에 없다 |
| P-3 | **컷오프 시점 상태로 비교한다.** D 대사는 D 안에서 일어난 승인·취소만 비교하고, 컷오프(D+1 00:00) 이후의 변경은 다음 날 대사 대상이다. 대사를 언제 실행하든 같은 날짜는 같은 결과가 나와야 한다 |
| P-4 | 불일치 유형은 4가지: ① **우리만 있음**(우리는 승인·취소했는데 PG에 해당 orderId가 없음) ② **PG만 있음**(PG에는 있는데 우리 DB에 해당 orderId가 없음) ③ **금액 불일치** ④ **상태 불일치**(같은 orderId인데 D 안의 승인·취소 여부가 다름) |
| P-5 | 날짜 경계 보정: 한쪽에만 있는 후보는 확정 전에 상대편에서 orderId로 다시 찾아, 인접 날짜에 허용 시각 차이(기본 5분) 이내로 있으면 **불일치에서 제외**한다 |
| P-6 | 불일치는 **기록 + 알람만** 한다. 자동 보정(망취소·상태 수정)은 하지 않는다 — 사람이 확인해 처리한다 |
| P-7 | 같은 날짜를 다시 대사하면 그 날짜의 이전 결과를 **새 결과로 교체**한다. 날짜당 결과는 한 벌만 존재한다 |
| P-8 | 관리자는 날짜를 지정해 대사를 **수동 재실행**할 수 있다 |
| P-9 | 배치가 중간에 실패하면 처음부터가 아니라 **실패 지점부터 재시작**한다. 이미 처리한 구간은 다시 처리하지 않는다 |
| P-10 | 알람: 불일치 1건 이상 → **P1**, 배치 실패·미실행 → **P1** |

### 시나리오
| ID | 구분 | 상황 | 동작 | 결과 |
|----|------|------|------|------|
| S-1 | 정상 | D의 모든 승인·취소가 PG 파일과 일치 | 대사 실행 | 불일치 0건, 대사 완료 기록 |
| S-2 | 정상 | D에 승인, D+1에 취소된 결제를 D+1 대사 전(D+1 03:00)에 D 대사 | D 대사 실행 | D에서는 승인끼리 일치. 취소는 D+1 대사에서 비교 (P-3) |
| S-3 | 예외 | PG는 승인, 우리는 FAILED(승인 기록 없음) | 대사 실행 | ④ 상태 불일치 기록 + P1 알람 |
| S-4 | 예외 | PG 파일에 있는 orderId가 우리 DB에 없음 | 대사 실행 | ② PG만 있음 기록 + P1 |
| S-5 | 예외 | 우리는 승인인데 PG 파일·인접 날짜 어디에도 없음 | 대사 실행 | ① 우리만 있음 기록 + P1 |
| S-6 | 예외 | 같은 orderId, 금액이 다름 | 대사 실행 | ③ 금액 불일치 기록 + P1 |
| S-7 | 경계 | PG 23:59:59(D) 승인, 우리 00:00:01(D+1) 승인 | D·D+1 대사 | 양쪽 모두 불일치에서 제외 (P-5) |
| S-8 | 재실행 | 이미 대사한 D를 다시 실행 (그사이 D+1 이후 결제·취소가 계속 들어옴) | 수동 재실행 | 결과가 이전과 동일, 이전 결과는 교체 (P-3, P-7) |
| S-9 | 장애 | 처리 도중 실패 (예: N번째 구간에서 DB 오류) | 재시작 | 실패 구간부터 이어서 처리, 이미 처리한 구간은 중복 기록 없음, 최종 결과는 한 번에 성공했을 때와 동일 (P-9) |
| S-10 | 장애 | 대사일의 PG 파일이 없음 | 대사 실행 | 배치 실패 + P1. 파일이 들어온 뒤 수동 재실행 |
| S-11 | 동시성 | 대사 처리 중 결과 판정 대상의 상태가 바뀜 | 대사 실행 | 대상 건이 누락·중복되지 않음 |

### 화면
없음 (관리자 재실행은 API, 불일치는 결과 테이블·메트릭·알람으로 확인)

### 완료 기준
- [ ] 일치·4가지 불일치 유형이 시드 데이터로 판정된다 (S-1, S-3~S-6)
- [ ] 컷오프 이후 변경이 D 결과에 영향을 주지 않는다 — 재현 테스트: 현재 상태로 비교하는 구현에서는 실패, 컷오프 비교로 통과 (S-2, S-8)
- [ ] 날짜 경계 건이 불일치에서 제외된다 (S-7)
- [ ] 같은 날짜 재실행 시 결과가 동일하고 한 벌만 남는다 (S-8)
- [ ] 중간 실패 후 재시작하면 실패 지점부터 처리하고 최종 결과가 한 번에 성공한 것과 같다 — 재현 테스트: 한 트랜잭션 구현에서는 전체 롤백, chunk 커밋 + 재시작으로 통과 (S-9)
- [ ] 처리 중 상태 변경이 있어도 대상이 누락되지 않는다 — 재현 테스트: OFFSET 페이징에서 누락, 키셋 페이징으로 통과 (S-11)
- [ ] PG 파일이 없으면 실패하고 P1 알람 규칙이 있다 (S-10)
- [ ] 불일치 1건 이상이면 P1 알람 규칙이 있다 (P-10)
- [ ] 시드 데이터로 기준선(소요 시간·처리량)을 측정해 기록한다 (BL-008 판단 근거)

### 범위 밖
- 불일치 자동 보정 — 실시간 보정은 serverA 재대사 스케줄러 담당, 잘못된 자동 보정은 돈이 움직여 위험 (P-6)
- 불일치 조회·해결 처리 API/화면 — 필요해지면 백로그
- 성능·실시간 간섭 개선(커넥션 풀 분리, 속도 조절, chunk 크기 튜닝) — BL-008(조건부, 측정 후)
- Read Replica, 배치 인스턴스 분리 — 현 규모에서 불필요
- 실제 PG 파일 수신(SFTP 등) — 실제 PG 미연동, 모의 PG가 파일 생성
- 판매자 정산(수수료·지급) — 단일 판매자 쇼핑몰이라 불필요 (BL-009 종료)

## 2. 결정 기록
| 질문 | 결정 | 이유 |
|------|------|------|
| PG 거래 내역 형태 | 일자별 CSV 파일 | 실제 PG 대사 파일 방식과 같고, 대량 시드 생성이 쉬우며, 재실행 시 입력이 같아 멱등 검증에 유리 |
| 불일치 처리 | 기록 + 알람만 | 자동 보정은 기존 재대사 스케줄러와 겹치고 오보정 시 돈이 움직임 |
| 불일치 유형 | 4가지(우리만·PG만·금액·상태) | 취소까지 비교해야 환불 정합성(BL-029)도 검증됨 |
| 주기 | 매일 새벽 전일분 + 수동 재실행 | 재실행은 PG 파일 지연 복구 수단이자 멱등 확인 수단 |
| 컷오프 이후 변경 | 컷오프 시점 상태로 비교 | 현재 상태 비교는 실행 시점마다 결과가 달라져 멱등 위반, 자정 직후 취소가 오탐 |
| 날짜 경계 | orderId로 인접 날짜 재확인, 허용 차이 내면 제외 | 우리와 PG의 기록 시각 차이로 인한 오탐 방지 |
| 재실행 결과 | 교체 | 날짜당 결과 한 벌 — 최신 결과 선택 규칙 불필요 |
| 알람 등급 | 불일치·배치 실패 모두 P1 | 대사는 사후 감시라 즉시 대응 대상 아님 |
| 금액 없는 옛 결제(`amount` null) | 금액 비교에서 제외 (기록만) | 확장-축소 이전 데이터, 금액 기준 없음 |
| 허용 시각 차이 | 5분 (기록만, 설정값) | 우리와 PG 기록 시각 차이는 초 단위라 넉넉한 값 |
| 실행 시각 | 매일 03:00 (기록만, 설정값) | 트래픽 한가한 시간, Saga 미확정 건(최대 30분)이 모두 확정된 뒤 |

## 3. 설계

배치는 **serverC 안**에 둔다 (`payments`를 소유한 서버. 별도 인스턴스 분리는 범위 밖).

### 대사 기준: 이벤트 단위 비교
- 비교 키: `(orderId, 이벤트 종류 APPROVE/CANCEL)`. D의 우리 이벤트 = `approved_at` 또는 `cancelled_at`이 D 안에 있는 건, D의 PG 이벤트 = D 파일의 줄.
- 판정 (P-4를 이벤트 단위로 구체화 — 변경 이력 참조):
  | 우리 | PG | 판정 |
  |---|---|---|
  | 있음 | 있음, 금액 같음 | 일치 |
  | 있음 | 있음, 금액 다름 | ③ 금액 불일치 (`amount` null이면 비교 생략) |
  | 있음 | 없음 | ① 우리만 있음 |
  | 결제 건 자체 없음 | 있음 | ② PG만 있음 |
  | 결제 건은 있으나 D에 그 이벤트 없음 | 있음 | ④ 상태 불일치 (예: PG 승인, 우리 FAILED) |
- 날짜 경계(P-5): 한쪽에만 있는 후보는 상대편에서 같은 키를 찾아 시각 차이가 허용치(5분) 이내면 제외한다.
  - 불변식: **PG 이벤트 시각 ≤ 우리 기록 시각.** 우리는 PG 응답을 받은 뒤 기록하므로 경계 건은 "PG는 D, 우리는 D+1" 방향으로만 생긴다. 그래서 D 대사에 D+1 파일(아직 없음)이 필요 없다. D+1 대사의 "우리만" 후보 확인용으로 D 파일의 마지막 5분 줄만 함께 적재한다.
- 실시간 망취소된 금액 불일치 건(PG 승인 금액 ≠ 주문 금액 → 즉시 취소, 우리 FAILED)은 양쪽 모두 승인+취소 이벤트가 있고 금액이 달라 ③으로 다시 잡힌다. 대사는 기록 차이를 남길 뿐 처리 여부는 사람이 본다 (발생 빈도 극히 낮음).

### DB 변경 (serverC, Flyway)
**V4 — `payments` 이벤트 시각**
```sql
ALTER TABLE payments
  ADD COLUMN approved_at  DATETIME(6) NULL COMMENT 'PG 승인 기록 시각 (대사 기준)',
  ADD COLUMN cancelled_at DATETIME(6) NULL COMMENT 'PG 취소 기록 시각 (대사 기준)',
  ADD INDEX idx_payments_approved_at (approved_at, id),
  ADD INDEX idx_payments_cancelled_at (cancelled_at, id);
```
- 기록 지점: PAID 전환 시 `approved_at`, PAID→CANCELLED 전환 시 `cancelled_at`, 금액 불일치 망취소(FAILED) 시 둘 다. 상태 UPDATE와 같은 문장에서 `NOW(6)`.
- 기존 행은 채우지 않는다 (모의 PG의 과거 거래 파일이 없어 비교 대상이 없음). 대사는 마이그레이션 이후 거래부터 유효.
- 인덱스는 키셋 페이징(`WHERE approved_at >= ? AND approved_at < ? AND (approved_at, id) > (?, ?) ORDER BY approved_at, id`)용.

**V5 — 대사 테이블**
```sql
-- 모의 PG의 원장 (PG사 내부 흉내). 대사 배치는 읽지 않고, 파일 생성기만 읽는다.
CREATE TABLE mock_pg_transactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(10) NOT NULL,      -- APPROVE / CANCEL
  amount BIGINT NULL,
  occurred_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_mock_pg_tx (order_id, event_type),
  KEY idx_mock_pg_tx_occurred (occurred_at)
);

-- PG 파일을 적재한 사본. 재시작·키셋 조회·orderId 재확인용
CREATE TABLE pg_settlement_lines (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recon_date DATE NOT NULL,             -- 이 줄을 적재한 대사일
  order_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(10) NOT NULL,
  amount BIGINT NULL,
  occurred_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pg_line (recon_date, order_id, event_type),
  KEY idx_pg_line_order (order_id, event_type)
);

CREATE TABLE reconciliation_mismatches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recon_date DATE NOT NULL,
  order_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(10) NOT NULL,
  mismatch_type VARCHAR(20) NOT NULL,   -- OURS_ONLY / PG_ONLY / AMOUNT / STATUS
  our_amount BIGINT NULL,
  pg_amount BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_mismatch (recon_date, order_id, event_type)
);
```
- `uk_pg_line`·`uk_mismatch` + `INSERT IGNORE`: chunk 재시도·재시작 시 같은 줄을 두 번 써도 한 벌 (S-9).
- **V6 — Spring Batch 메타 테이블** (`BATCH_*`, 공식 `schema-mysql.sql`). `spring.batch.jdbc.initialize-schema: never` — 스키마는 Flyway가 관리.

### 모의 PG 파일
- `MockPgClient`가 승인·취소를 확정할 때 `mock_pg_transactions`에 기록한다. **별도 트랜잭션(REQUIRES_NEW)** — 외부 PG는 우리 트랜잭션이 롤백돼도 되돌아가지 않으므로.
- `MockPgFileExporter`(스케줄, 매일 00:10): 전일 원장을 `{pg-file-dir}/pg-transactions-YYYYMMDD.csv`로 쓴다. 임시 파일에 쓰고 완료 후 이름 변경(반쯤 쓴 파일을 배치가 읽지 않게).
- CSV: `order_id,event_type,amount,occurred_at` (헤더 1줄).
- 기준선 측정용 시드 생성기는 `payments` 행과 CSV를 직접 만든다 (원장 경유 X).

### 배치 Job `pgReconciliationJob` (파라미터 `reconDate`, 수동 재실행 시 `requestedAt` 추가)
| Step | 내용 | 정합성 요건 |
|---|---|---|
| 1 clear | 해당 날짜의 `reconciliation_mismatches`·`pg_settlement_lines` 삭제 | P-7 교체. 재시작 시 완료된 Step은 다시 돌지 않음 |
| 2 load | D 파일 전체 + D-1 파일의 마지막 5분 줄 → `pg_settlement_lines` (chunk, `INSERT IGNORE`) | 파일 없음 → Step 실패 (S-10). 읽은 줄 수를 저장해 재시작 시 이어 읽음 |
| 3 ours→PG | D의 우리 승인·취소 이벤트를 **키셋 페이징**으로 읽어 PG 사본과 비교 → ①③ 기록 | chunk 커밋, 진행 위치(마지막 키) 저장 → 실패 지점부터 재시작 (S-9). 조회 조건은 이벤트 시각뿐이라 처리 중 상태가 바뀌어도 대상이 흔들리지 않음 (S-11) |
| 4 PG→ours | D의 PG 줄을 키셋 페이징으로 읽어 `payments` 조회 → ②④ 기록 | 같음 |
| 5 report | 유형별 건수 메트릭, 마지막 성공 대사일 갱신 | |
- 실행: `@Scheduled` 매일 03:00, `reconDate = 어제`. 여러 serverC 인스턴스가 동시에 띄워도 같은 `reconDate` JobInstance는 하나만 생긴다(JobRepository 유일 제약).
- 재시작: 같은 날짜의 마지막 실행이 FAILED면 **같은 파라미터로 다시 실행 → Spring Batch 재시작**(실패 Step의 마지막 커밋 위치부터). COMPLETED면 `requestedAt`을 붙여 새 실행(교체).
- 컷오프(P-3)는 조회 범위 `[D 00:00, D+1 00:00)`가 이벤트 시각 기준이라 자동 성립 — 컷오프 이후 변경은 `cancelled_at`이 D+1이 되어 D 범위에 들어오지 않는다.
- chunk 크기·커넥션 풀은 기본값으로 시작하고 기준선 측정 후 BL-008에서 다룬다.

### API (serverC, 계약 파일에 없음 — serverC는 OpenAPI 계약 대상 아님)
```yaml
/api/v1/payments/admin/reconciliations:
  post:
    summary: 대사 수동 실행 (FAILED면 재시작, COMPLETED면 새 실행)
    parameters:
      - { name: date, in: query, required: true, schema: { type: string, format: date } }
    responses:
      '202': { description: 실행 시작, body: { jobExecutionId: long, mode: RESTART|NEW } }
      '400': { description: date가 오늘 이후 }
      '409': { description: 같은 날짜 대사가 실행 중 }
```
- 기존 관리자 API(`/api/v1/payments/admin/cancel-failures`)와 같은 경로·보호 방식.

### 이벤트
변경 없음

### 메트릭·알람 (`monitoring/`·`helm/` 2벌, 규칙은 각각 별도)
| 메트릭 | 알람 |
|---|---|
| `business_reconciliation_mismatch_total{type}` (Counter, 대사 1회마다 건수만큼 증가) | `increase(...[1d]) > 0` → P1 `ReconciliationMismatch` |
| `business_reconciliation_job_failed_total` (Counter) | `increase(...[1h]) > 0` → P1 `ReconciliationJobFailed` |
| `business_reconciliation_last_success_date_seconds` (Gauge, 마지막 COMPLETED 대사일을 Batch 메타 테이블에서 읽음 — 재기동해도 유지) | `time() - 값 > 30h` → P1 `ReconciliationNotRun` |

### 장애 시 동작
| 실패 지점 | 동작 |
|------|------|
| PG 파일 없음 | Step 2 실패 → Job FAILED + P1. 파일 생성 후 수동 실행 = 재시작 |
| 처리 중 DB 오류·서버 재기동 | Job FAILED(재기동 시 STARTED로 남은 실행은 기동 시 FAILED로 정리) → 다음 수동/스케줄 실행이 실패 Step의 마지막 커밋 위치부터 재시작 |
| chunk 쓰기 중 실패 | 그 chunk만 롤백, 재시작 시 그 chunk부터. `INSERT IGNORE`로 중복 없음 |
| 같은 날짜 동시 실행 | 실행 중이면 API 409, 스케줄은 JobRepository가 거부 |
| 모의 PG 원장 기록 실패 | 결제 처리는 계속(외부 PG 흉내), 그 건은 다음 대사에서 ①로 드러남 |
| 파일 생성기 실패 | 파일 없음 → 위 첫 줄 |

### 프론트 영향
없음

### 새 기술
**Spring Batch** (`spring-boot-starter-batch`, `spring-batch-test` — Boot 관리 버전, **새 의존성 승인 필요**)
| 대안 | 판단 |
|---|---|
| Spring Batch | chunk 커밋·진행 위치 저장·실패 지점 재시작·동시 실행 방지를 JobRepository가 제공. 대사 배치의 표준 |
| 직접 구현 (스케줄러 + JdbcTemplate + 진행 위치 테이블) | 의존성 없음. 재시작·진행 위치·동시 실행 방지를 직접 만들어야 하고, 그 자체가 버그 원천 |
→ Spring Batch 채택. 단 재현 테스트의 "단순 구현"(한 트랜잭션·OFFSET·현재 상태 비교)은 테스트 안에서 비교 대상으로만 만든다.

### 구현 단계 (PR 여러 개)
| PR | 백로그 | 범위 |
|----|------|------|
| ① | BL-045 | 대사 기반 데이터: V4 이벤트 시각 기록, 모의 PG 원장(V5 중 `mock_pg_transactions`) + CSV 파일 생성기 |
| ②-a | BL-050 (BL-046) | 대사 Job 본체: Spring Batch 도입(V6·V7), Step 1~5, 재현 테스트 3종(S-8·S-9·S-11) |
| ②-b | BL-051 (BL-046) | 운영 연결: 수동 실행 API, 스케줄 03:00, 기동 시 정리, 메트릭·알람 |
| ③ | BL-047 | 시드 생성기 + 기준선 측정(perf-compare) → BL-008 판단 |

진행 상태는 백로그 하위 항목에서 관리한다 (이 표에 적지 않는다).

마지막 PR에서 할 일: BL-002 완료 처리, `docs/portfolio.md` 정합성 3종(재현 테스트 전후) 기록 제안, `docs/architecture/arch-snapshot.md` 배치·테이블 반영

## 4. 변경 이력 (확인 이후)
| 날짜 | 변경 | 이유 |
|------|------|------|
| 2026-11-17 | P-4 ①·④를 이벤트 단위로 구체화: ① = 우리 이벤트에 대응하는 PG 이벤트 없음, ④ = PG 이벤트는 있고 우리 결제 건도 있으나 D에 그 이벤트가 없음 | 파일이 하루치라 "PG에 orderId가 없음"을 다른 날짜까지 확인할 수 없음. 판정을 D 안에서 닫아야 재실행 결과가 같음 |
| 2026-11-17 | 이벤트 시각은 DB `NOW(6)`가 아니라 앱 시계(`LocalDateTime.now()`)를 파라미터로 기록 | 모의 PG 원장과 같은 JVM 시계여야 "PG 시각 ≤ 우리 기록 시각" 불변식이 시계 어긋남과 무관하게 성립 (BL-045 /plan) |
| 2026-11-17 | 이벤트 시각은 JVM 기본 타임존이 아니라 KST로 고정(`ReconciliationTime.now()`). 거래 파일은 serverC 로컬 디렉터리 — 단일 인스턴스 전제, 다중 파드 저장소는 BL-048 | PR #62 리뷰 C2·C3: 파드 TZ 미설정 시 대사 범위(P-1 KST)와 9시간 어긋남, 파일이 파드마다 따로 생김 |
| 2026-11-17 | 마이그레이션 번호: PR①은 V4(시각)·V5(`mock_pg_transactions`), PR②는 V6(`pg_settlement_lines`·`reconciliation_mismatches`)·V7(Batch 메타) | PR 분할에 맞춤 |
| 2026-11-17 | PR②를 ②-a(Job 본체, BL-050)·②-b(운영 연결, BL-051)로 분할. chunk 크기 500(설정값), D-1 파일 없음은 경고 후 진행, Step 3은 승인·취소 정렬 키가 달라 3a·3b로 분리 | 변경 파일 20개 초과 예상, 위험 변경과 운영 연결의 리뷰 분리 (BL-046 /plan) |
