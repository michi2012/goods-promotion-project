# 맥락 노트: PG 대사 PR②-b — 대사 운영 연결 (BL-051)

## [진행 중] BL-002 PR②-b — BL-051

## 왜 이 방식을 선택했는가
- 명세(`docs/specs/pg-reconciliation-batch.md`, 설계 확인 ☑)의 API·스케줄·메트릭·알람을 그대로 구현한다. /plan에서 사용자가 정한 것 (2026-11-17):
  - **400 기준은 "date ≥ 오늘(KST)"**. 오늘 파일은 내일 00:10에 생성되므로, 오늘을 실행하면 FAILED + P1 알람만 난다.
  - **E2E는 실패·재시작 경로만** 다룬다. HTTP만 쓰는 E2E로는 컨테이너 안에 PG 파일을 넣을 수 없다. 성공 경로와 불일치 메트릭은 Testcontainers 테스트가 맡는다.
  - **스케줄 실행 시 이미 COMPLETED면 건너뛴다.** FAILED면 재시작, 실행 중이면 건너뛴다 — 같은 파라미터로 실행할 때 Spring Batch가 보이는 동작과 같다.
- **비동기 실행기는 Bean으로 등록하지 않는다**: 202로 실행 ID를 바로 돌려주려면 비동기 실행이 필요하다. 그런데 Boot 3.5의 Batch 자동 설정은 `JobLauncher` Bean이 이미 있는지 보지 않고 자기 것을 등록하므로, Bean을 하나 더 만들면 같은 타입이 둘이 되어 `JobLauncherTestUtils`의 주입이 깨진다. 그래서 서비스 안에서 `TaskExecutorJobLauncher`를 직접 만든다. 스케줄도 같은 실행기를 쓴다 — 스케줄러 스레드는 2개뿐이고(`SchedulingConfig`) 다른 스케줄과 같이 쓰므로, 대사 작업이 오래 붙잡으면 안 된다.
- **날짜별 실행 조회는 메타 테이블 JDBC**: `JobExplorer`는 파라미터로 실행을 찾지 못한다. COMPLETED 뒤의 재실행은 `requestedAt`이 붙은 별개 인스턴스라, 날짜 기준으로 묶어 "가장 최근 실행"을 찾아야 한다.
- **메트릭은 Job 종료 리스너에서**: Step 5에서 세면 Step 재시도·재시작 때 두 번 셀 수 있다. Job 종료는 실행마다 한 번이다.
- **게이지 = 대사일 D의 끝(D+1 00:00 KST) epoch 초**: 명세 문구대로 "대사일"(D 00:00)로 잡으면, 정상이어도 다음 날 03:00 실행 직전에 `time() - 값`이 51시간이 되어 30시간 알람이 매일 울린다. D+1 00:00이면 정상일 때 최대 약 27시간이다.
- 성공 기록이 없으면 게이지는 NaN이다 — 새 환경에서 알람이 계속 울리지 않게. 대신 "한 번도 실행된 적 없음"은 이 알람으로 잡지 못한다.

## 검토했으나 채택하지 않은 대안
### 비동기 `JobLauncher` Bean 등록 (`@Primary`나 이름으로 구분)
- 왜 안 썼나: 기존 Job 테스트와 Boot 자동 설정의 주입에 손을 대야 한다. 쓰는 곳이 서비스 한 곳뿐이다.
### `JobOperator.restart(executionId)`
- 왜 안 썼나: Operator 안의 실행기(동기)를 쓰므로 비동기 요구와 맞지 않는다. 실패한 실행의 파라미터를 그대로 넘겨 `run`해도 Spring Batch가 같은 방식으로 재시작한다.
### 스케줄러가 Job을 동기로 실행
- 왜 안 썼나: 스케줄러 스레드 2개를 다른 스케줄과 같이 쓴다.

## 기존 코드베이스 컨벤션
- 관리자 보호: `X-User-Role == ROLE_ADMIN`, 아니면 `AdminForbiddenException` → 403 (`controller/PaymentAdminController.java`)
- 예외 → `exception/GlobalExceptionHandler.java`, 본문은 문자열 메시지
- 메트릭: `Counter.builder("business_..._total")`를 생성자에서 등록 (`service/PaymentCancelService.java`)
- 스케줄: `@Scheduled(cron = "${...}", zone = "Asia/Seoul")`, 날짜는 `ReconciliationTime.ZONE` (`scheduler/MockPgFileExporter.java`)
- Batch 테스트: `batch/ReconciliationJobTestSupport.java` (Testcontainers, `JobLauncherTestUtils`)
- 알람: `monitoring/prometheus/alert-rules.yml` Tier1 그룹, `labels: severity / tier`. helm 사본은 `helm/promotion-monitoring/files/alert-rules.yml`
- E2E: `backend-e2e/payment-cancel-admin.spec.ts` (serverC 직접 호출, `X-User-Role` 헤더)

## 관련 파일/위치
- `serverC/.../batch/ReconciliationJobConfig.java` — Job 정의, 리스너 등록 위치
- `serverC/.../repository/ReconciliationMismatchRepository.java` — `countByType(date)` (메트릭 건수)
- `serverC/src/main/resources/db/migration/V7__*` — Batch 메타 테이블
- `serverC/src/main/resources/application.yaml` — `spring.batch.job.enabled: false`, `reconciliation.*`

## 외부 참조
- 명세 `docs/specs/pg-reconciliation-batch.md` "배치 Job"·"API"·"메트릭·알람"·"장애 시 동작"
