# 계획서: PR #55 리뷰 반영 — 환불 재구매 차단·paid_at null·Redis 재고 이중 복구 + 리뷰 미리보기 형식 (BL-041)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-041 (`docs/backlog.md`), PR #55 리뷰 https://github.com/michi2012/goods-promotion-project/pull/55#pullrequestreview-5915956641 , 명세 `docs/specs/user-refund.md`

## [진행 중] BL-041

### 목표
머지 후 리뷰한 PR #55(BL-029 PR②-b)의 [필수] 1·[제안] 2를 고친다: 환불한 사용자의 재구매가 구매 후 1시간 뒤에도 막히게 하고(P-12), `paid_at`이 없는 PAID 주문의 환불 요청이 500이 되지 않게 하고, 환불 완료 재시도 시 Redis 재고가 두 번 오르지 않게 한다. 함께 `/pr-review` 미리보기를 원문 그대로 보여주도록 형식을 정한다.

### 성공 기준
- [ ] 환불 완료 시 구매 표시가 만료 없이(`TTL = -1`) 남고, 그 뒤 `tryMarkUserPurchased`가 false (`RedisStockServiceTest` — 실제 Redis)
- [ ] 환불 완료 재시도(방어 키 이미 있음) 시 DB 전이·Outbox는 다시 하되 Redis 재고는 올리지 않음 (`RefundServiceTest`)
- [ ] `paidAt == null`인 PAID 주문은 `updatedAt` 기준으로 기간 판단 (`RefundServiceTest`)
- [ ] serverA 전체 테스트 + 로컬 백엔드 E2E 3개 시나리오 회귀 통과
- [ ] `/pr-review` SKILL.md 3단계에 미리보기 형식(위치 → 코드 몇 줄 → 리뷰어 원문 전체 → 분리된 작성 세션 의견) 명시

### 비범위 (Out of Scope)
- 1인 1회 구매 표시 자체가 1시간만 유효한 문제 (환불 안 한 사용자도 1시간 뒤 재구매 가능) → BL-042
- 환불 완료 실패 알람 (BL-038), E2E 테스트 타임아웃 (BL-039)
- [사소] 2건 (주석 정정·import 정리)은 반영하지 않음

### 단계별 작업 계획

#### 단계 1: Redis 메서드 — 영구 구매 표시 + 환불 재고 복구 방어 키
- 변경 파일: `serverA/.../service/RedisStockService.java`, 테스트 `RedisStockServiceTest.java`
- 변경 내용: `markUserPurchasedPermanently(userId, goodsId)` — 같은 키를 TTL 없이 SET. `tryMarkRefundRestored(orderId)` — `refund:restored:{orderId}` SETNX(TTL 1일, Kafka 재시도 구간보다 충분히 김), 처음 잡았을 때만 true.
- 검증: `.\gradlew.bat :serverA:test --tests "*RedisStockServiceTest"` (Testcontainers Redis)
- 롤백: `git checkout -- <파일>`
- 예상 소요: 짧음

#### 단계 2: RefundService 반영 (C1·C2·C3)
- 변경 파일: `serverA/.../service/RefundService.java`, 테스트 `RefundServiceTest.java`
- 변경 내용: `completeRefund` — 조건부 전이 1행 → DB 재고 증가 → 구매 표시 영구화(재고 복구보다 먼저) → 방어 키를 처음 잡았을 때만 Redis `releaseStock` → 스냅샷·상태 Outbox. 기간 판단 기준 시각 = `paidAt`, 없으면 `updatedAt`.
- 검증: `:serverA:test --tests "*RefundServiceTest"`
- 롤백: `git checkout -- <파일>`
- 예상 소요: 짧음

#### 단계 3: `/pr-review` 미리보기 형식
- 변경 파일: `.claude/skills/pr-review/SKILL.md` (3단계)
- 검증: 육안
- 예상 소요: 짧음

#### 단계 4: 빌드 + 로컬 E2E 회귀
- 검증: `:serverA:build` → server-a 이미지 재빌드 → `backend-e2e/ npm test` (user-service 기동 상태 유지)
- 예상 소요: 보통

#### 단계 5: 문서
- 변경 파일: 명세(P-12 구현 방식·변경 이력), 백로그(BL-041 진행 중·BL-042 등록 — 완료), 3종 문서, PR #55 코멘트 답글(새 PR 링크)
- 예상 소요: 짧음

### 리스크 및 대응
- 리스크: 방어 키 SETNX 후 `releaseStock` 실패(Redis 부분 장애) 시 재시도해도 Redis 미복구 → 대응: Saga 보상과 같은 한계로 받아들임, 로그. 이중 복구(초과 판매 위험)보다 덜 위험
- 리스크: 영구 구매 표시가 Redis 재시작·축출로 사라짐 → 대응: 다른 Redis 키와 같은 조건. DB 기반 영구 차단은 BL-042에서 정책과 함께 검토
- 리스크: 구매 표시를 재고 복구보다 늦게 쓰면 그 사이 재구매 가능 → 대응: 순서를 고정하고 테스트(InOrder)로 확인

### 의존성
- 새 의존성 없음, 마이그레이션 없음
