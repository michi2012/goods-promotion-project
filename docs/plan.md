# 계획서: PG 대사 PR① — 대사 기반 데이터 (BL-045)

- 작성일: 2026-11-17
- 관련 이슈/티켓: BL-045 (BL-002의 하위 항목, `docs/backlog.md`), 명세 `docs/specs/pg-reconciliation-batch.md` (설계 확인 ☑)

## [진행 중] BL-002 PR① — BL-045

### 목표
대사 배치(PR②)가 비교할 양쪽 데이터를 만든다: 우리 쪽은 `payments`의 승인·취소 시각, PG 쪽은 모의 PG 원장과 거기서 만든 일자별 CSV 파일.

### 성공 기준
- [ ] PAID 전환 시 `approved_at`, PAID→CANCELLED 전환 시 `cancelled_at`, 금액 불일치 망취소 시 둘 다 기록된다 — `PaymentRepositoryTest`, `PaymentServiceTest`, `PaymentCancelServiceTest`
- [ ] 모의 PG가 승인(애매한 응답이지만 내부 처리된 건 포함)·신규 취소를 `mock_pg_transactions`에 기록하고, 바깥 트랜잭션이 롤백돼도 남는다 — `MockPgLedgerTest`, `MockPgClientTest`
- [ ] 생성기가 지정 날짜의 원장을 `pg-transactions-YYYYMMDD.csv`(헤더 + `order_id,event_type,amount,occurred_at`)로 임시 파일 → 이름 변경 방식으로 쓴다 — `MockPgFileExporterTest`
- [ ] serverC 전체 테스트 통과 — `.\gradlew.bat :serverC:test`
- [ ] 로컬 E2E: 구매·환불 후 `payments` 시각 컬럼과 원장 행 확인, 생성기 실행 후 CSV 확인

### 비범위
- 대사 Job·Spring Batch·대사 테이블(V6·V7)·수동 실행 API·알람 → BL-046
- CSV 파일 수동 재생성 API → BL-046 수동 실행 API에서 함께 검토 (S-10 복구)
- 시드 생성기·기준선 측정 → BL-047
- 기존 `payments` 행 시각 채우기 (명세: 하지 않음)
- docker-compose·helm 볼륨 (EKS 재가동 시)
- `MockPgClient`의 인메모리 저장소 교체

### 단계별 작업 계획

#### 단계 1: 마이그레이션 V4·V5
- 변경 파일: `serverC/src/main/resources/db/migration/V4__payment_event_times.sql`, `V5__mock_pg_transactions.sql`
- 내용: 명세 DDL 그대로 (`payments` 시각 2컬럼 + 인덱스 2개, `mock_pg_transactions`)
- 검증: 단계 2 리포지토리 테스트에서 Flyway가 적용되는지(Testcontainers) 확인
- 롤백: 파일 삭제 (아직 어느 DB에도 미적용)
- 예상 소요: 짧음

#### 단계 2: `PaymentRepository` 시각 기록 메서드
- 변경 파일: `PaymentRepository.java`, `PaymentRepositoryTest.java`
- 내용: `markPaid(orderId, approvedAt)`, `markCancelled(orderId, cancelledAt)`(PAID일 때만, 바뀐 행 수 반환), `markFailedAfterNetCancel(orderId, approvedAt, cancelledAt)` 추가. `updateStatusIfCurrent` 제거(유일 사용처가 `markCancelled`로 대체)
- 검증: `.\gradlew.bat :serverC:test --tests "*PaymentRepositoryTest"`
- 롤백: git checkout
- 예상 소요: 짧음

#### 단계 3: 서비스에서 시각 기록
- 변경 파일: `PaymentService.java`, `PaymentCancelService.java`, 각 테스트
- 내용: 승인 → `markPaid(now)`, 금액 불일치 망취소 → `markFailedAfterNetCancel(승인 직후 now, 취소 직후 now)`, `completeCancel` → `markCancelled(now)`. 시각은 앱 시계(`LocalDateTime.now()`)
- 검증: `--tests "*PaymentServiceTest" --tests "*PaymentCancelServiceTest" --tests "*PaymentCancelRetryIntegrationTest"`
- 롤백: git checkout
- 예상 소요: 보통

#### 단계 4: 모의 PG 원장
- 변경 파일: `MockPgLedger.java`(신규, `REQUIRES_NEW`, `INSERT IGNORE`, 날짜 범위 조회), `MockPgClient.java`, `MockPgLedgerTest.java`(신규), `MockPgClientTest.java`
- 내용: 승인 확정·신규 취소 시 원장 기록. 기록 실패는 결제 흐름을 막지 않고 로그만 (명세 장애 시 동작)
- 검증: `--tests "*MockPgLedgerTest" --tests "*MockPgClientTest"`
- 롤백: git checkout + 신규 파일 삭제
- 예상 소요: 보통

#### 단계 5: CSV 파일 생성기
- 변경 파일: `MockPgFileExporter.java`(신규), `MockPgFileExporterTest.java`(신규), `application.yml`(`mock-pg.file-dir`, `mock-pg.export.cron`)
- 내용: 매일 00:10(Asia/Seoul) 전일분 export. `export(LocalDate)`는 public — 임시 파일에 쓰고 `ATOMIC_MOVE`로 이름 변경, 이미 있으면 교체
- 검증: `--tests "*MockPgFileExporterTest"` (`@TempDir`)
- 롤백: git checkout + 신규 파일 삭제
- 예상 소요: 보통

#### 단계 6: 전체 테스트 + 로컬 E2E
- 검증: `.\gradlew.bat :serverC:test` (백그라운드), E2E는 e2e skill 기준 — 서버 기동 전 V4·V5 자동 적용 사용자 확인
- 예상 소요: 김

### 리스크 및 대응
- `MockPgClient` 안에서 원장 기록이 바깥 트랜잭션에 묶임 → 별도 빈 + `REQUIRES_NEW`, 롤백 후에도 남는지 테스트로 확인
- 원장 기록 예외가 결제를 실패시킴 → try/catch 로그, 테스트로 확인
- 커넥션 추가 사용(REQUIRES_NEW는 커넥션 하나 더 씀) → 모의 PG 한정, 풀 영향은 BL-047 측정에서 관찰
- Flyway V4·V5가 로컬 payment DB에 자동 적용 → E2E 기동 전 사용자 확인

### 의존성
- Docker (Testcontainers·E2E)
- 새 Gradle 의존성 없음 (Spring Batch는 BL-046)
