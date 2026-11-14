# 계획서: 결제조회 UNKNOWN 최대 대기 시간 + 알람 (BL-006)

## [완료] BL-006 (PR #43)

- 작성일: 2026-11-14
- 관련 이슈/티켓: 백로그 BL-006

### 목표
serverC 장애로 결제조회가 계속 UNKNOWN이면, Saga 생성 후 30분(설정 가능)이 지난 시점에 주문을 EXPIRED로 끝내 재고를 반납하고, UNKNOWN 지속과 강제 만료를 알람으로 알린다.

### 성공 기준
- [ ] 생성 후 30분 미만 + UNKNOWN → 지금처럼 건너뛰고 `saga.timeout.payment_unknown`만 증가 (단위 테스트)
- [ ] 생성 후 30분 이상 + UNKNOWN → `handleSagaFailure(orderId, "TIMEOUT")` 호출 + `saga.timeout.payment_unknown_expired`·`saga.expired.total` 증가 (단위 테스트)
- [ ] 알람 2개(P2 UNKNOWN 지속, P1 강제 만료)가 compose·helm 알람 파일 양쪽에 있고 문법이 맞다 (`promtool check rules`, `helm template`)
- [ ] 로컬 E2E: serverC 정지 상태에서 구매 → 상한(E2E용 짧은 값) 경과 후 주문 EXPIRED + 재고 복구 → serverC 재기동 후 결제가 CANCELLED로 정리됨
- [ ] `gradlew :serverA:test` 전체 통과

### 비범위
- `OrderReconciliationScheduler.reconcilePendingOrders`의 UNKNOWN 무한 재시도 (Redis 상태 유실 + serverC 장애 동시 발생) → 조건부 백로그로 기록
- serverC 장애가 24시간을 넘는 경우의 자동 환불 (BL-003 재대사 상한 밖 — P1 알람으로 수동 대응)
- 알람 라우팅(Alertmanager 수신처) 변경
- 3분 타임아웃(`TIMEOUT_MS`) 값 변경

### 단계별 작업 계획

#### 단계 1: `SagaTimeoutScheduler` 최대 대기 추가 + 단위 테스트
- 변경 파일: `serverA/src/main/java/promotion/serverA/scheduler/SagaTimeoutScheduler.java`, `serverA/src/test/java/promotion/serverA/scheduler/SagaTimeoutSchedulerTest.java`
- 변경 내용: `@Value("${saga.timeout.payment-unknown-max-minutes:30}")` 필드 추가. UNKNOWN 분기에서 `now - createdAt >= 상한`이면 `handleSagaFailure(orderId, "TIMEOUT")` + `saga.timeout.payment_unknown_expired`·`saga.expired.total` 증가, 아니면 기존 동작. 테스트: 상한 이상 UNKNOWN → 만료, 상한 미만 UNKNOWN → 기존 테스트 유지(값은 `ReflectionTestUtils`로 주입).
- 검증: `.\gradlew.bat :serverA:test --tests "promotion.serverA.scheduler.SagaTimeoutSchedulerTest"`
- 롤백: 두 파일 `git restore`
- 예상 소요: 짧음

#### 단계 2: 알람 규칙 2개 추가
- 변경 파일: `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: Tier1에 추가.
  - P2 `PaymentQueryUnknownPersisting`: `increase(saga_timeout_payment_unknown_total[5m]) > 0`, `for: 5m` — serverC 결제조회 불가 지속
  - P1 `SagaForceExpiredOnUnknown`: `increase(saga_timeout_payment_unknown_expired_total[5m]) > 0` — 결제 상태를 모른 채 만료. serverC 복구 후 24시간 안에 자동 망취소되는지 확인 필요
- 검증: `docker run --rm --entrypoint promtool -v <monitoring/prometheus>:/r prom/prometheus check rules /r/alert-rules.yml` (helm 파일도 동일), `helm template helm/promotion-monitoring`
- 롤백: 두 파일 `git restore`
- 예상 소요: 짧음

#### 단계 3: 로컬 E2E (사용자 확인 후)
- 내용: serverA에 `saga.timeout.payment-unknown-max-minutes=4` 주입해 기동 → serverC 정지 → 구매(PENDING) → 3분 후 UNKNOWN 로그·카운터, 4분 후 EXPIRED + 재고 복구 확인 → serverC 재기동 → 쌓인 결제 요청 처리 후 늦은 성공 보상으로 serverC 결제 CANCELLED 확인. 이어서 `gradlew :serverA:test` 전체.
- 롤백: 주입 설정 제거, 컨테이너 재기동
- 예상 소요: 보통

#### 단계 4: 문서 갱신
- 변경 파일: `docs/design-notes.md`(UNKNOWN 제약 "최대 대기·알람은 미구현" → 결정 추가), `docs/purchase-saga-flow.md`·`docs/arch-snapshot.md`(타임아웃 스케줄러 설명에 상한 반영), `docs/backlog.md`
- 검증: grep으로 "미구현" 문구 제거 확인
- 롤백: `git restore`
- 예상 소요: 짧음

### 리스크 및 대응
- 상한 도달 시 serverC에서 실제 승인된 결제가 남을 수 있음 → 늦은 성공 보상 + BL-003 재대사(24시간)가 망취소. 24시간 초과 장애는 P1 알람으로 수동 대응
- 만료 시 재고 반납 후 결제가 승인되면 고객은 "결제 후 취소"를 겪음 → 초과판매보다 안전하므로 수용 (PAID로 되돌리지 않음)
- `saga.expired.total` 증가로 기존 SagaExpired 알람도 함께 울림 → 의도된 동작 (EXPIRED 처리 사실 자체는 같음)

### 의존성
- BL-003 `reconcileTerminatedOrders`, `SagaResultConsumer`의 늦은 성공 보상 (이미 main)
