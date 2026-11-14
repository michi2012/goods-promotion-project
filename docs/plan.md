# 계획서: 결과 이벤트 유실 시 종료 주문 재대사 (BL-003)

- 작성일: 2026-11-14
- 관련 이슈/티켓: 백로그 BL-003

## 목표
주문이 EXPIRED/FAILED로 끝났는데 serverC 결제는 PAID로 남은 경우(늦은 `payment-result` 또는 `payment-cancel`이 CDC에서 유실)를 serverA가 주기적으로 찾아 `payment-cancel`(망취소)을 발행한다.

## 성공 기준
- [ ] 생성 후 10분~24시간 지난 EXPIRED/FAILED 주문을 조회하는 리포지토리 메서드가 범위 경계대로 동작 (`OrderRepositoryTest`, Testcontainers)
- [ ] 스케줄러: serverC 조회 PAID → 망취소 발행 / NOT_PAID·UNKNOWN → 아무것도 안 함 (`OrderReconciliationSchedulerTest`)
- [ ] 서비스: 주문이 EXPIRED/FAILED일 때만 `payment-cancel` Outbox 저장 + `saga.reconcile_cancel.total` 증가 (`SagaOrchestratorServiceTest`)
- [ ] 로컬 E2E: 정상 구매(PAID) 후 serverA 주문을 DB에서 EXPIRED로 바꾸면 → 재대사가 망취소 → serverC 결제 CANCELLED (주기·하한을 프로퍼티로 짧게 설정)
- [ ] `gradlew :serverA:test` 통과

## 비범위 (Out of Scope)
- serverC 측 재대사(serverC 스케줄러 + serverA 조회 API) — 검토 후 미채택 (context.md)
- 중복 망취소 시 실제 PG 응답 처리 (BL-005, 조건부)
- 24시간을 넘긴 CDC 장애 — 장애 대응 영역
- 일괄 조회 API(serverC에 여러 orderId 한 번에 조회) — 실패 주문이 소수라 개별 조회로 충분
- 백엔드 E2E(`backend-e2e/`) 시나리오 추가 — 이상 상태를 만들려면 DB 직접 조작이 필요해 HTTP 기반 E2E 범위 밖. 1회 로컬 검증으로 대체

## 단계별 작업 계획

### 단계 1: 리포지토리 조회 메서드
- 변경 파일: `serverA/.../repository/OrderRepository.java`, `serverA/src/test/.../repository/OrderRepositoryTest.java`(신규)
- 변경 내용: `findByStatusInAndCreatedAtBetween(List<OrderStatus>, from, to)` 추가 — 기존 인덱스 `(status, created_at)` 활용. 테스트는 기존 `GoodsRepositoryTest`와 같은 Testcontainers 구성, `created_at`은 네이티브 UPDATE로 조정.
- 검증: `gradlew :serverA:test --tests "*OrderRepositoryTest"` (Docker 필요)
- 롤백: 두 파일 되돌림
- 예상 소요: 짧음

### 단계 2: 서비스 보상 메서드
- 변경 파일: `serverA/.../service/SagaOrchestratorService.java`, `SagaOrchestratorServiceTest.java`
- 변경 내용: `compensateLostPaymentResult(orderId)` 추가 — 주문을 다시 읽어 EXPIRED/FAILED면 `payment-cancel` Outbox 저장 + `saga.reconcile_cancel.total` 증가. 기존 `compensateLatePaymentSuccess`와 로직이 비슷하지만 카운터를 구분하려고 별도 메서드로 둔다.
- 검증: `gradlew :serverA:test --tests "*SagaOrchestratorServiceTest"`
- 롤백: 두 파일 되돌림
- 예상 소요: 짧음

### 단계 3: 스케줄러 재대사 메서드
- 변경 파일: `serverA/.../scheduler/OrderReconciliationScheduler.java`, `OrderReconciliationSchedulerTest.java`
- 변경 내용: `reconcileTerminatedOrders()` 추가. `@Scheduled(fixedDelayString = "${saga.reconcile.terminated.interval-ms:600000}")`, 하한 `saga.reconcile.terminated.min-age-minutes:10`, 상한 24시간(상수). 조회 결과 PAID → `compensateLostPaymentResult`, NOT_PAID(CANCELLED 포함)·UNKNOWN → 건너뜀.
- 검증: `gradlew :serverA:test --tests "*OrderReconciliationSchedulerTest"`
- 롤백: 두 파일 되돌림
- 예상 소요: 보통

### 단계 4: 문서 반영
- 변경 파일: `docs/purchase-saga-flow.md`(안전망 섹션), `docs/design-notes.md`("결과 이벤트가 영영 오지 않는 경우" 항목 갱신), `docs/arch-snapshot.md`(스케줄러 목록 — 관련 부분만)
- 검증: 내용 검토
- 롤백: 문서 되돌림
- 예상 소요: 짧음

### 단계 5: 로컬 E2E 검증
- 변경 파일: 없음 (임시 compose override는 scratchpad에 둔다)
- 내용: 전체 스택 기동(serverA에 `interval-ms=30000`, `min-age-minutes=1` 주입) → 정상 구매로 PAID 확인 → serverA DB에서 해당 주문을 `EXPIRED`로 UPDATE → 1~2분 내 serverC 결제 `CANCELLED` 확인 + serverA 로그·메트릭 확인. 이어서 `gradlew :serverA:test` 전체 실행.
- 롤백: 스택 내림 (로컬 DB는 익명 볼륨이라 초기화)
- 예상 소요: 보통

## 리스크 및 대응
- CDC가 오래 멈추면 주기마다 `payment-cancel`이 outbox에 쌓여 복구 시 중복 취소 → serverC 취소는 CANCELLED 덮어쓰기라 모의 PG에서 멱등. 실제 PG는 BL-005로 관리.
- EXPIRED/FAILED 주문 수만큼 serverC 개별 조회 (하루 최대 144회/건) → 실패 주문은 소수라 허용. 커지면 일괄 조회 API 검토.
- 정상적인 늦은 결과와 동시에 실행될 경우 망취소 2회 → 위와 같은 멱등성에 의존. 하한 10분으로 겹칠 가능성을 낮춤.

## 의존성
- serverC 결제조회 API (`PaymentQueryClient` 기존 사용)
- Outbox → Debezium CDC → `payment-cancel` → serverC `PaymentCancelConsumer` (기존)
