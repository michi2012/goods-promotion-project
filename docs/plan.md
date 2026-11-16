# 계획서: 사용자 환불 PR②-b — serverA 환불 API·환불 완료 처리 + E2E (BL-029)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-029 (`docs/backlog.md`), 명세 `docs/specs/user-refund.md` (설계 확인 ☑)

## [완료] BL-029 PR②-b (PR #55)

### 목표
사용자가 serverA `POST /api/v1/orders/{orderId}/refund`로 본인의 PAID 주문(결제 후 7일 이내)을 환불 요청하면 `payment-cancel`이 발행되고, serverC의 `payment-cancelled`를 받아 주문이 REFUNDED가 되며 재고(DB·Redis)가 주문 수량만큼 복구된다. 명세 S-1·S-3~S-6·P-12를 serverA에서 구현하고 백엔드 E2E로 확인한다.

### 성공 기준
- [ ] PAID로 바뀔 때 `orders.paid_at`이 기록된다 (Saga 완료·결제조회 보정 모두) — `OrderRepositoryTest`, `SagaOrchestratorServiceTest`
- [ ] 환불 API: 정상 200 `REFUND_REQUESTED` + Outbox(`payment-cancel`, `order-status-update`), 남의 주문/없는 주문/없는 loginId 404 `ORDER_NOT_FOUND`, PAID 아님 409 `REFUND_NOT_ALLOWED_STATUS`, 7일 초과 409 `REFUND_PERIOD_EXPIRED`, 이미 접수·완료 200 현재 상태, user-service 장애 503, `X-User-Id` 없음 401 — `RefundServiceTest`, `RefundControllerTest`
- [ ] `payment-cancelled` 수신 시 REFUND_REQUESTED 주문만 REFUNDED + 재고 복구, 그 외(망취소·중복)는 무시, 구매 표시는 유지 — `PaymentCancelledConsumerTest`, `RefundServiceTest`
- [ ] 계약 `contracts/server-a-openapi.yaml`과 실제 스펙이 일치 — serverA `OpenApiContractTest`
- [ ] 로컬 백엔드 E2E: 구매 → 환불 동시 2회(둘 다 200) → serverB REFUNDED → serverA 재고 정확히 1회 복구 → 같은 상품 재구매 거절 + 기존 시나리오 회귀 — `backend-e2e/ npm test`

### 비범위 (Out of Scope)
- cs-bot `requestRefund`/`trackRefundStatus` 전환, 직접 발행 제거, FAQ, 프론트 화면 → PR③
- arch-snapshot·purchase-saga-flow·design-notes의 `refund_fatal` 설명 갱신, 포트폴리오 → PR③(마지막 PR)
- 게이트웨이 경유 E2E (환불 E2E는 serverA 직접 호출 — 게이트웨이 라우트는 #52 테스트로 고정)
- serverA admin API 역할 검사 (BL-030)

### 단계별 작업 계획

#### 단계 1: `paid_at` 기록 (V4 + PAID 전이) — 🔶 마이그레이션 포함, 단계별 승인
- 변경 파일: `serverA/src/main/resources/db/migration/V4__add_orders_paid_at.sql`(신규), `entity/Order.java`, `entity/OrderStatus.java`, `repository/OrderRepository.java`, `service/SagaOrchestratorService.java`, 테스트 `OrderRepositoryTest`, `SagaOrchestratorServiceTest`
- 변경 내용: `orders.paid_at DATETIME NULL` 추가 + 기존 PAID 행 `paid_at = updated_at` (ALTER만, `/db-migration` 관점 검토). `OrderStatus`에 `REFUND_REQUESTED`·`REFUNDED`. PAID 전이 전용 쿼리(`status=PAID, paidAt=:now WHERE status=PENDING`)로 `tryCompleteSaga`·`reconcileToPaid` 교체. 조건부 상태 전이 쿼리 `updateStatusIfCurrent(orderId, from, to)` 추가.
- 검증: `.\gradlew.bat :serverA:test --tests "*OrderRepositoryTest" --tests "*SagaOrchestratorServiceTest"`
- 롤백: `git checkout -- <파일>`, V4 삭제
- 예상 소요: 보통

#### 단계 2: user-service 조회 클라이언트
- 변경 파일: `client/UserQueryClient.java`(신규), `application-k8s.yml`(SimpleDiscoveryClient에 user-service), 테스트 `UserQueryClientTest`(신규), `K8sDiscoveryConfigTest`
- 변경 내용: `GET /internal/api/users/{loginId}` → 숫자 ID. 404면 "사용자 없음", 그 외 실패는 조회 불가로 구분해 돌려준다 (`PaymentQueryClient` 방식).
- 검증: `:serverA:test --tests "*UserQueryClientTest" --tests "*K8sDiscoveryConfigTest"`
- 롤백: `git checkout -- <파일>`, 신규 삭제
- 예상 소요: 짧음

#### 단계 3: 환불 요청 서비스 + 예외
- 변경 파일: `service/RefundService.java`(신규), `exception/RefundException.java`(신규), `exception/GlobalExceptionHandler.java`, `application.yaml`(`refund.period-days: 7`), 테스트 `RefundServiceTest`(신규)
- 변경 내용: 가정 2·3·4 (context.md). user-service 조회는 트랜잭션 밖, 검사·조건부 UPDATE·Outbox는 트랜잭션 안. 동시 요청에서 진 쪽은 다시 읽어 현재 상태 200.
- 검증: `:serverA:test --tests "*RefundServiceTest"`
- 롤백: `git checkout -- <파일>`, 신규 삭제
- 예상 소요: 보통

#### 단계 4: 환불 API + 계약
- 변경 파일: `controller/RefundController.java`(신규), `dto/response/RefundResponse.java`(신규), `contracts/server-a-openapi.yaml`, 테스트 `RefundControllerTest`(신규), `OpenApiContractTest`
- 변경 내용: `POST /api/v1/orders/{orderId}/refund` + `X-User-Id` 필수(없으면 401). 계약에 경로·`RefundResponse` 추가.
- 검증: `:serverA:test --tests "*RefundControllerTest" --tests "*OpenApiContractTest"` (계약 테스트는 Testcontainers — Docker 기동 중)
- 롤백: `git checkout -- <파일>`, 신규 삭제
- 예상 소요: 보통

#### 단계 5: `payment-cancelled` 소비 → REFUNDED + 재고 복구
- 변경 파일: `kafka/PaymentCancelledConsumer.java`(신규), `dto/PaymentCancelledMessage.java`(신규), `service/RefundService.java`(완료 처리 메서드), `config/KafkaTopicConfig.java`(`payment-cancelled.DLT` 파티션 1 — 단계 5 착수 시 발견, 소비 측 선언 관례, 사용자 승인 2026-11-16), 테스트 `PaymentCancelledConsumerTest`(신규), `RefundServiceTest`
- 변경 내용: 가정 5. `payment-cancelled.DLT` 리스너(로그 + `business_refund_complete_failed_total` 메트릭).
- 검증: `:serverA:test --tests "*PaymentCancelledConsumerTest" --tests "*RefundServiceTest"`
- 롤백: `git checkout -- <파일>`, 신규 삭제
- 예상 소요: 보통

#### 단계 6: 환불 E2E 시나리오
- 변경 파일: `backend-e2e/refund-flow.spec.ts`(신규), `backend-e2e/preflight.ts`(user-service health), `backend-e2e/SCENARIOS.md`
- 변경 내용: 가정 7. 사용자는 user-service 내부 API로 매번 새로 만든다.
- 검증: 단계 7에서 실행 (`npx tsc --noEmit` 등 정적 확인만 먼저)
- 예상 소요: 보통

#### 단계 7: 전체 빌드 + 로컬 E2E
- 검증: `:serverA:build`(백그라운드) → `:serverA:bootJar` → `user-service`·`mysql-user` 기동 → server-a 이미지만 재빌드(V4 자동 적용 — 사용자 승인됨) → `backend-e2e/ npm test` (전체 3개 시나리오)
- 예상 소요: 김

### 리스크 및 대응
- 리스크: PAID 전이 쿼리 교체로 기존 Saga 테스트 깨짐 → 대응: 단계 1에서 호출부 2곳·테스트를 함께 수정
- 리스크: 동시 환불 요청에서 Outbox 중복 → 대응: 조건부 UPDATE 1행일 때만 Outbox 저장, 0행이면 재조회만 (단위 테스트 + E2E 동시 2회)
- 리스크: `payment-cancelled` 처리 실패 시 주문이 REFUND_REQUESTED에 남음 → 대응: 명세대로 Kafka 재시도 → DLT 로그·메트릭(관리자 확인). 알람 규칙 추가는 범위 밖(발견 사항으로 기록 여부 판단)
- 리스크: user-service 미기동으로 E2E 실패 → 대응: preflight에 user-service health 추가, 단계 7에서 기동
- 리스크: 변경 파일 약 25개 → 대응: ②-a로 이미 분리. 커밋을 단계 묶음별로 나눠 리뷰 단위를 작게

### 의존성
- 새 Gradle 의존성 없음
- serverC `payment-cancelled` 발행(PR① #48), 게이트웨이 라우트·serverB enum(#52) 머지됨
- user-service 내부 API(`/internal/api/users`)
