# 맥락 노트: 결제조회 UNKNOWN 최대 대기 시간 + 알람 (BL-006)

## [진행 중] BL-006

### 왜 이 방식을 선택했는가
- PR #22에서 타임아웃 시 결제조회가 UNKNOWN이면 EXPIRED를 미루도록 했다(승인된 결제가 망취소 없이 남는 것 방지). 상한이 없어서 serverC 장애가 길면 주문 PENDING·재고·유저 구매 마킹이 계속 묶이고, `saga.timeout.payment_unknown` 카운터를 보는 알람도 없었다.
- 이제는 만료해도 안전망이 있다: 늦은 `payment-result(success)` → `compensateLatePaymentSuccess`, 결과 이벤트 유실 → BL-003 `reconcileTerminatedOrders`(생성 후 10분~24시간 EXPIRED/FAILED를 재조회해 PAID면 망취소).
- 사용자 결정(2026-11-14): 상한 도달 시 강제 EXPIRED + 알람, 상한 30분.
- 상한 전 serverC 복구 → 주문은 아직 PENDING이라 기존대로 `reconcileToPaid`(PAID 보정). 상한 후 복구 → 이미 EXPIRED라 망취소(환불). 기준은 "재고가 아직 그 고객 몫으로 잡혀 있느냐" — 만료 시 재고를 반납해 다른 구매자에게 팔렸을 수 있으므로 PAID로 되돌리면 초과판매가 된다.

### 검토했으나 채택하지 않은 대안
#### 알람만 추가, PENDING 유지
- 왜 안 썼나: 재고 묶임이 그대로라 BL-006의 절반만 해결.
#### 만료된 주문을 serverC 복구 후 PAID로 되돌리기
- 왜 안 썼나: 반납된 재고가 이미 팔렸을 수 있어 초과판매. `updateStatusIfPending`도 PENDING일 때만 바꾼다.

### 기존 코드베이스 컨벤션
- 설정 주입: `@Value("${...:기본값}")` 필드 (`OrderReconciliationScheduler.terminatedMinAgeMinutes`)
- 메트릭: `meterRegistry.counter("saga.xxx").increment()` → Prometheus `saga_xxx_total`
- 테스트: Mockito `@InjectMocks` (`SagaTimeoutSchedulerTest`), `@Value` 필드는 `ReflectionTestUtils.setField`
- 알람: compose(`monitoring/prometheus/alert-rules.yml`)와 helm(`helm/promotion-monitoring/files/alert-rules.yml`) 두 벌. helm 쪽이 규칙이 더 많아 내용이 다르므로 파일 복사가 아니라 각각 추가

### 관련 파일/위치
- `serverA/.../scheduler/SagaTimeoutScheduler.java` — 3분 타임아웃 + 결제조회 분기
- `serverA/.../service/SagaOrchestratorService.java` — `handleSagaFailure`(EXPIRED + 재고 복구), `compensateLostPaymentResult`
- `serverA/.../scheduler/OrderReconciliationScheduler.java` — PENDING 재대사, 종료 주문 재대사
- `docs/design-notes.md` — "EXPIRED 전에 serverC 결제를 조회..." 결정과 제약
