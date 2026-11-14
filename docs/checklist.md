# 체크리스트: 결제조회 UNKNOWN 최대 대기 시간 + 알람 (BL-006)

## [진행 중] BL-006

- 마지막 업데이트: 2026-11-14

### 진행 상황
- [x] 단계 1: `SagaTimeoutScheduler` 최대 대기 + 단위 테스트
  - [x] 검증 통과 (`:serverA:test --tests SagaTimeoutSchedulerTest` — 6건 통과)
- [x] 단계 2: 알람 규칙 2개 (compose·helm)
  - [x] 검증 통과 (`promtool check rules` ×2 — 26·35 rules SUCCESS, `helm template` 렌더 결과에 2개 포함)
- [x] 단계 3: 로컬 E2E (상한 4분 주입) — 기준선 백엔드 E2E 통과 / serverC 정지 후 구매(재고 5→3) → 16:17 UNKNOWN 재시도 로그 → 16:18:06 강제 EXPIRED, 재고 5 복구, `saga_timeout_payment_unknown_expired_total` 증가 → serverC 재기동 → 결제 PAID → 늦은 결제 성공 보상 망취소 → 16:19:00 CANCELLED
  - [x] `:serverA:test` 전체 (92건 통과)
- [x] 단계 4: 문서 갱신 (design-notes, purchase-saga-flow, arch-snapshot, backlog)

### 최종 검증
- [x] serverA 전체 테스트 통과
- [x] plan.md "비범위" 침범 없음
- [x] `git diff`로 의도하지 않은 변경 없음

### 발견 사항 (커밋 전 백로그로 이전)
- `reconcilePendingOrders` UNKNOWN 무한 재시도 → 백로그 BL-028(조건부)로 이전 완료
- E2E 환경 함정(Debezium 오프셋 vs 익명 MySQL 볼륨, Error 1236) → `.claude/skills/e2e/project.md`에 기록
