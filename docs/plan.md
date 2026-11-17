# 계획서: PG 대사 PR②-b — 대사 운영 연결 (BL-051)

## [완료] BL-002 PR②-b — BL-051 (PR #64)

- 작성일: 2026-11-17
- 관련 항목: BL-051 (BL-046 하위, 상위 BL-002) / 명세 `docs/specs/pg-reconciliation-batch.md` 구현 단계 ②-b

## 목표
PR②-a에서 만든 `pgReconciliationJob`을 실제로 돌릴 수 있게 한다: 관리자 수동 실행 API, 매일 03:00 스케줄, 기동 시 끊긴 실행 정리, 메트릭 3종과 P1 알람 3종.

## 성공 기준
- [ ] `POST /api/v1/payments/admin/reconciliations?date=` — 관리자 아님 403, date ≥ 오늘(KST) 400, 실행 중 409, 실행 기록 없음 202 NEW, FAILED 202 RESTART(그 실행의 파라미터로), COMPLETED 202 NEW(`requestedAt` 추가). `@WebMvcTest` + 서비스 단위 테스트 통과
- [ ] 03:00 스케줄: 어제 날짜 기준으로 실행 기록 없음 → 실행, FAILED → 재시작, COMPLETED·실행 중 → 건너뜀. 단위 테스트 통과
- [ ] 기동 시 STARTED·STOPPING 상태로 남은 실행(Job·Step)을 FAILED로 바꾸고 실패 카운터를 올린다. Testcontainers 테스트 통과
- [ ] 메트릭: Job COMPLETED → `business_reconciliation_mismatch_total{type}`이 유형별 건수만큼 증가하고 게이지가 D+1 00:00 KST(epoch 초)로 갱신된다. FAILED → `business_reconciliation_job_failed_total` +1. 게이지는 재기동 시 메타 테이블에서 복원된다. Testcontainers 테스트 통과
- [ ] 알람 3종이 `monitoring/`·`helm/` 두 벌 모두에 있다. promtool로 검사(가능하면)하고, `helm template`이 통과한다
- [ ] 백엔드 E2E: 403 → 400 → 파일 없는 과거 날짜 202 NEW → 실패 카운터 증가 → 같은 날짜 202 RESTART

## 비범위 (Out of Scope)
- 실행 상태·불일치 조회 API (명세 "범위 밖") — 상태는 메트릭·로그로 확인
- 다중 파드 동시 실행 방지·파일 공유 저장소 — BL-048 (단일 인스턴스 전제)
- E2E 성공 경로(PG 파일을 컨테이너에 넣기) — Testcontainers 테스트가 담당 (사용자 결정)
- 시드·기준선 측정 — BL-047, 성능 튜닝 — BL-008
- arch-snapshot 반영, BL-002 완료, 포트폴리오 — 마지막 PR(BL-047)

## 단계별 작업 계획

### 단계 1: 메타 조회 + 실행 서비스
- 변경 파일: `repository/ReconciliationRunRepository.java`(신규, `BATCH_*` 테이블 JDBC 조회: 날짜별 마지막 실행 / 마지막 COMPLETED 대사일), `service/ReconciliationRunService.java`(신규, 모드 판단 + 비동기 실행 + `synchronized`), 예외 `ReconciliationAlreadyRunningException`·`InvalidReconciliationDateException` + `GlobalExceptionHandler`(409·400), 테스트(Repository Testcontainers, Service Mockito)
- 변경 내용: 실행 기록 없음 → NEW(`reconDate`) / 실행 중 → 409 / FAILED·STOPPED → 같은 파라미터로 RESTART / COMPLETED → API면 NEW(`requestedAt`), 스케줄이면 건너뜀. 비동기 실행기는 서비스 안에서 만든다 (Bean으로 등록하지 않음 — 맥락 노트 참고).
- 검증: `.\gradlew.bat :serverC:test --tests "*ReconciliationRun*"`
- 롤백: 신규 파일 삭제, 핸들러 변경 되돌리기
- 예상 소요: 김

### 단계 2: 관리자 API
- 변경 파일: `controller/ReconciliationAdminController.java`(신규), `dto/ReconciliationRunResponse.java`(신규), `ReconciliationAdminControllerTest`(신규)
- 변경 내용: `X-User-Role` 확인 → 서비스 호출 → 202 `{jobExecutionId, mode}`. 기존 `PaymentAdminController`와 같은 보호 방식.
- 검증: `--tests "*ReconciliationAdminControllerTest"`
- 롤백: 신규 파일 삭제
- 예상 소요: 짧음

### 단계 3: 메트릭 리스너 + 기동 시 정리
- 변경 파일: `batch/ReconciliationMetrics.java`(신규, `JobExecutionListener` + 카운터 2종 + 게이지), `batch/ReconciliationStartupCleaner.java`(신규, `ApplicationReadyEvent`), `ReconciliationJobConfig`(리스너 등록, Step 5 주석), 테스트
- 변경 내용: COMPLETED → 불일치 유형별 카운터 증가 + 게이지 = (D+1 00:00 KST) epoch 초. FAILED → 실패 카운터. 게이지 초기값은 메타 테이블의 마지막 COMPLETED 대사일, 없으면 NaN. 기동 시 끊긴 실행을 FAILED로 바꾸고 실패 카운터 +1.
- 검증: `--tests "*ReconciliationMetrics*" --tests "*ReconciliationStartupCleaner*" --tests "*ReconciliationJobTest"` (기존 Job 테스트도 다시 돌린다)
- 롤백: 신규 파일 삭제, JobConfig 되돌리기
- 예상 소요: 보통

### 단계 4: 스케줄러
- 변경 파일: `scheduler/ReconciliationScheduler.java`(신규), `application.yaml`(`reconciliation.cron: "0 0 3 * * *"`), 테스트
- 변경 내용: `@Scheduled(cron, zone = Asia/Seoul)` → `runService.runScheduled(어제)`. 예외는 로그만 남긴다 (실패는 리스너가 메트릭으로 알린다).
- 검증: `--tests "*ReconciliationSchedulerTest"`
- 롤백: 신규 파일 삭제, yaml 되돌리기
- 예상 소요: 짧음

### 단계 5: 알람 규칙 (2벌)
- 변경 파일: `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: `ReconciliationMismatch`(`increase(...[1d]) > 0`), `ReconciliationJobFailed`(`increase(...[1h]) > 0`), `ReconciliationNotRun`(`time() - gauge > 30h`). 모두 P1.
- 검증: promtool `check rules`(docker prometheus 이미지), `helm template helm/promotion-monitoring`
- 롤백: git 되돌리기
- 예상 소요: 짧음 (**인프라 변경 — 단계별 승인**)

### 단계 6: 백엔드 E2E + 서버 기동 검증
- 변경 파일: `backend-e2e/reconciliation-admin.spec.ts`(신규), `backend-e2e/SCENARIOS.md`
- 변경 내용: 403 → 400(오늘) → 파일 없는 무작위 과거 날짜 202 NEW → `/actuator/prometheus`에서 실패 카운터 증가를 기다림 → 같은 날짜 202 RESTART.
- 검증: serverC 재빌드·기동 후 `backend-e2e/ npm test -- reconciliation-admin`
- 롤백: 신규 파일 삭제
- 예상 소요: 보통

### 단계 7: 문서
- 변경 파일: 명세 변경 이력(게이지 = D+1 00:00, 400 = 오늘 포함, 스케줄은 COMPLETED면 건너뜀), 3종 문서, 백로그
- 검증: 리뷰
- 예상 소요: 짧음

## 리스크 및 대응
- `JobLauncher` Bean이 둘이 되어 기존 Job 테스트의 주입이 깨짐 → 비동기 실행기를 Bean으로 등록하지 않고 서비스 안에서 만든다
- 게이지 기준을 D 00:00으로 잡으면 정상 운영에서도 NotRun 알람이 매일 울림 → D+1 00:00 기준, 테스트로 고정
- 기동 시 정리가 다른 파드에서 실제로 돌고 있는 실행을 FAILED로 만듦 → 단일 인스턴스 전제(BL-048)를 코드 주석과 명세에 적는다
- 같은 날짜로 API를 동시에 호출해 NEW 실행이 2개 생김 → 서비스 `synchronized` + 같은 파라미터는 JobRepository가 거부
- 메타 테이블의 파라미터 저장 형식(Batch 5: `PARAMETER_VALUE` 문자열) 의존 → Repository Testcontainers 테스트로 고정

## 의존성
- PR②-a의 `pgReconciliationJob`·V7 Batch 메타 테이블 (main에 머지됨)
- 새 Gradle 의존성 없음
- E2E: docker compose 스택(serverC + mysql-c)
