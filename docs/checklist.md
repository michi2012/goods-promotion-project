# 체크리스트: 결과 이벤트 유실 시 종료 주문 재대사 (BL-003)

- 마지막 업데이트: 2026-11-14

## [진행 중] BL-003

### 진행 상황
- [x] 단계 1: `OrderRepository.findByStatusInAndCreatedAtBetween` + `OrderRepositoryTest`
  - [x] 검증 통과 (`gradlew :serverA:test --tests "*OrderRepositoryTest"` — 1/1, Testcontainers)
- [x] 단계 2: `SagaOrchestratorService.compensateLostPaymentResult` + 테스트
  - [x] 검증 통과 (`gradlew :serverA:test --tests "*SagaOrchestratorServiceTest"` — 18/18, 신규 4)
- [x] 단계 3: `OrderReconciliationScheduler.reconcileTerminatedOrders` + 테스트
  - [x] 검증 통과 (`gradlew :serverA:test --tests "*OrderReconciliationSchedulerTest"` — 8/8, 신규 4)
- [x] 단계 4: 문서 반영 (purchase-saga-flow 4-D, design-notes BL-003 결정, arch-snapshot 스케줄러 표·시퀀스)
- [x] 단계 5: 로컬 E2E (EXPIRED 강제 → serverC CANCELLED)
  - `backend-e2e` npm test 1/1 → 주문 `01a1d7f9…` PAID → serverA DB에서 EXPIRED로 UPDATE → 12:06:07 `[Saga 결과 유실] 망취소 발행` → 12:06:19 serverC `CANCELLED`, outbox `payment-cancel` 1건, `saga_reconcile_cancel_total 1.0`
  - EXPLAIN: `type=range`, `key=idx_orders_status_created`
  - 계획 외 수정 (사용자 승인 A): serverA `eureka.client.fetch-registry: false` → `true`. 로컬에서 `PaymentQueryClient`가 serverC를 못 찾아 항상 UNKNOWN이던 기존 결함 (K8s는 simple discovery라 무관). 이 PR을 막는 문제라 별도 커밋
  - [x] `gradlew :serverA:test` 전체 — 21개 클래스 91건 통과 (첫 시도는 전체 스택 + Testcontainers 동시 부하로 Docker Desktop 엔진 정지 → 재시작·스택 정지 후 재실행)

### 최종 검증
- [x] serverA 테스트 전체 통과 (91/91)
- [x] 로컬 E2E 통과
- [x] 변경 사항이 plan.md "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (15개 파일, 모두 계획 범위)

### 발견 사항 (커밋 전 백로그로 이전)
- E2E 중 환경 함정 → 이 PR에 반영 (사용자 승인 b): `.claude/skills/e2e/project.md` "로컬 스택 기동 시 주의"(bootJar 선행, 기동 대기, override 주입)
- 재발 방지 기록을 자동으로 남기는 규칙 → 이 PR 이후 별도 문서 PR (사용자 결정) → **백로그 BL-027로 이전**
- 다른 모듈(serverB·C·user-service·codebot)의 `fetch-registry: false`는 DiscoveryClient 조회가 없어 영향 없음 — 조치 불필요
