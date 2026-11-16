# 맥락 노트: 사용자 환불 PR②-b — serverA 환불 API·환불 완료 처리 + E2E (BL-029)

## [진행 중] BL-029 PR②-b

### 왜 이 방식을 선택했는가
명세(`docs/specs/user-refund.md`, 설계 확인 ☑)가 정책·API·이벤트·DB를 확정했다. 아래는 명세가 구현에 맡긴 부분의 가정이며 사용자가 승인했다 (2026-11-16).
1. **`paid_at` 기록 위치**: PAID 전이는 `Order.markAsPaid()`가 아니라 `OrderRepository.updateStatusIfPending(orderId, PAID)`(JPQL 벌크)라서, PAID 전이 전용 쿼리(`status=PAID, paidAt=:now WHERE status=PENDING`)로 바꾼다. 호출부는 `SagaOrchestratorService.tryCompleteSaga`·`reconcileToPaid`. FAILED·EXPIRED는 기존 쿼리 유지
2. **환불 요청 흐름**: user-service 조회(트랜잭션 밖) → 트랜잭션 안에서 주문 조회·본인 확인(아니면 404) → REFUND_REQUESTED/REFUNDED면 200 현재 상태 → PAID 아님 409 → 기간 초과 409 → 조건부 UPDATE(PAID→REFUND_REQUESTED) + Outbox(`payment-cancel`, `order-status-update`). 0행(동시 요청에서 진 쪽)이면 다시 읽어 현재 상태 200
3. **기간**: `paid_at + 7일 >= 지금`이면 허용 (7일째 포함), `refund.period-days: 7`
4. **예외**: HTTP 상태 + 에러 코드를 갖는 `RefundException` 신설 + 핸들러. 기존 `BusinessException`(항상 400)·`PromotionException`(코드=상태명)은 그대로
5. **완료 처리**: `payment-cancelled` 수신 → REFUND_REQUESTED→REFUNDED 조건부 UPDATE 1행일 때만 DB 재고 증가 + Redis `releaseStock` + Outbox(`stock-snapshot`, `order-status-update(REFUNDED)`). `releaseUserPurchase`는 하지 않음(P-12). 0행이면 무시. `payment-cancelled.DLT` 리스너는 로그 + 메트릭
6. **user-service 클라이언트**: `UserQueryClient` — `PaymentQueryClient`와 같은 DiscoveryClient·타임아웃(connect 3s / read 5s). 404 → `ORDER_NOT_FOUND`, 그 외 → 503 `USER_LOOKUP_UNAVAILABLE`
7. **E2E**: user-service 내부 API로 사용자 생성 → 상품(재고 5) → 2개 구매 → PAID → 환불 동시 2회(둘 다 200) → serverB REFUNDED → serverA 재고 5(정확히 1회 복구) → 재구매 거절. "PG 1회"는 재고 1회 복구로 갈음 (PG 멱등은 serverC 단위 테스트, ②-a 때 결정)
8. **V4 적용**: 로컬 E2E 때 새 serverA 기동으로 자동 적용 (사용자 승인)

### 검토했으나 채택하지 않은 대안
#### `Order.markAsPaid()`에서 `paid_at` 기록 (명세 문구 그대로)
- 왜 안 썼나: 실제 PAID 전이는 벌크 UPDATE라 엔티티 메서드를 거치지 않는다. 벌크를 엔티티 로드+변경으로 바꾸면 조건부 전이(멱등성)가 깨진다.
#### 기존 `BusinessException`에 HTTP 상태 추가
- 왜 안 썼나: 모든 하위 예외의 응답 코드에 영향이 간다. 수술적 변경 원칙상 환불 전용 예외로 한정한다.
#### 환불 E2E를 게이트웨이 경유로
- 왜 안 썼나: JWT 발급(user-service 로그인)까지 시나리오에 들어가 실패 원인이 넓어진다. 기존 E2E도 서버 직접 호출이다.

### 기존 코드베이스 컨벤션
- 상태 전이: 조건부 JPQL UPDATE + 반환 행 수로 멱등 (`OrderRepository.updateStatusIfPending`)
- 보상·재고 복구 + Outbox: `SagaOrchestratorService.handleSagaFailure`·`reconcileToFailed` (같은 트랜잭션에서 DB 재고·Redis·Outbox)
- 내부 HTTP 호출: `client/PaymentQueryClient.java` + `config/RestClientConfig.java`(`internalServiceClientBuilder`), 테스트 `PaymentQueryClientTest`
- K8s 정적 등록 검증: `config/K8sDiscoveryConfigTest.java`
- 컨슈머: `kafka/SagaResultConsumer.java` (`@KafkaListener` + `objectMapper.readValue`), DLT: `kafka/PurchaseDltConsumer.java`
- 마이그레이션: `V3__add_price_and_order_amount.sql` (ALTER, AFTER 미사용 — INSTANT DDL)
- 계약: `contracts/server-a-openapi.yaml` + `contract/OpenApiContractTest.java`(Testcontainers)

### 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/repository/OrderRepository.java`, `entity/Order.java`, `entity/OrderStatus.java`
- `serverA/src/main/java/promotion/serverA/service/SagaOrchestratorService.java`, `RedisStockService.java`(`releaseStock`, `getCurrentStock`)
- `serverA/src/main/java/promotion/serverA/exception/GlobalExceptionHandler.java`
- `serverA/src/main/resources/application.yaml`, `application-k8s.yml`
- user-service `InternalUserController`(`GET /internal/api/users/{userId}` 404=`UserNotFoundException`, `POST /internal/api/users` → `{id, userId, email, username}`)
- `backend-e2e/purchase-flow.spec.ts`(구매 패턴), `preflight.ts`, `SCENARIOS.md`
- serverA `GET /api/v1/goods/{id}` → `{id, name, price, stock}` (DB 재고 — E2E 재고 확인용)

### 외부 참조
- 명세 `docs/specs/user-refund.md` — 정책 P-1~12, 시나리오 S-1~12, "DB 변경"·"API"·"이벤트"·"장애 시 동작"·"구현 단계"
