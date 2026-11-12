# 계획서: 결제 금액 기본기 (가격·주문 금액 스냅샷·결제 금액·PG 승인 금액 검증)

- 작성일: 2026-11-12
- 관련 이슈/티켓: 없음 (결제 도메인 학습 로드맵 1단계. 2단계 = Spring Batch 정산)

## 목표
상품 가격부터 PG 승인까지 금액을 서버가 계산·전달·검증하도록 한다. 클라이언트는 금액을 보내지 않고, serverC는 PG 승인 금액이 주문 금액과 다르면 망취소 후 실패 처리한다.

## 성공 기준
- [ ] 구매 요청에 금액 필드가 없고, serverA가 주문 저장 단계에서 `단가 × 수량`으로 총액을 계산해 `Order`에 스냅샷한다 (`OrderCommandServiceTest`)
- [ ] 가격 0원 상품 주문은 결제 요청 없이 FAILED + 재고 복구된다 (`OrderCommandServiceTest`)
- [ ] serverC가 PG 승인 금액 ≠ 주문 금액이면 망취소 호출 + FAILED + `payment-result(false)`를 남기고, 금액 없는 옛 메시지는 검증 없이 기존 방식대로 처리하며 경고 메트릭을 올린다 (`PaymentServiceTest`)
- [ ] 금액·통화가 DB(`orders`, `payments`)에 저장된다 (`PaymentRepositoryTest`, Flyway 마이그레이션 적용 후 확인)
- [ ] 상품 조회 API·OpenAPI 계약에 `price`가 있고, 구매 화면에 단가와 예상 금액이 표시된다 (Vitest + E2E)

## 비범위 (Out of Scope)
- 수수료·부가세·반올림 정책 (2단계 정산)
- 부분 취소, 다중 통화 (통화는 `KRW` 고정, 컬럼만 둠)
- serverB 주문 상태 뷰·cs-bot 응답에 금액 노출
- 금액 필수화 (확장-축소 패턴 마지막 단계 — 후속 작업)
- 구매 접수 경로(`acceptPurchase`) 변경

## 단계별 작업 계획

### 단계 1: serverA DB 마이그레이션 + 엔티티 필드 ⚠️ 단계별 승인
- 변경 파일: `serverA/src/main/resources/db/migration/V3__add_price_and_order_amount.sql`(신규), `entity/Goods.java`, `entity/Order.java`
- 변경 내용 요약: `goods.price BIGINT NOT NULL DEFAULT 0`, `orders.unit_price BIGINT NULL`, `orders.total_amount BIGINT NULL`, `orders.currency VARCHAR(3) NULL` 추가 (ADD COLUMN만). 엔티티에 대응 필드와 빌더 파라미터 추가.
- 검증 방법: `.\gradlew.bat :serverA:compileJava` + `/db-migration`으로 안전성 검토. 실제 DB 적용은 사용자가 수행.
- 롤백 방법: 마이그레이션 파일 삭제 + 엔티티 변경 되돌리기 (DB 미적용 상태)
- 예상 소요: 짧음

### 단계 2: serverA 상품 API에 가격 추가 + 계약
- 변경 파일: `dto/request/CreateGoodsRequest.java`, `dto/response/CreateGoodsResponse.java`, `dto/response/GoodsResponse.java`, `service/GoodsService.java`, `contracts/server-a-openapi.yaml`, `GoodsControllerTest.java`(+ 필요 시 GoodsService 테스트)
- 변경 내용 요약: 상품 생성 시 `price` 필수(1원 이상, Bean Validation). 상품 조회·생성 응답에 `price` 포함. 계약 `GoodsResponse`에 `price`(int64) 추가.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.controller.GoodsControllerTest"`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 3: serverA 주문 저장 단계 금액 스냅샷 + 결제 요청 메시지
- 변경 파일: `service/OrderCommandService.java`, `dto/PaymentRequestMessage.java`(신규), `OrderCommandServiceTest.java`
- 변경 내용 요약: `processNewOrder`에서 `Goods` 가격 조회 → `unitPrice`·`totalAmount`·`currency=KRW`를 `Order`에 저장. `payment-request`에는 `PurchaseMessage` 대신 금액 필드가 추가된 `PaymentRequestMessage`를 Outbox 저장. 가격 0원이면 결제 요청 없이 `SagaOrchestratorService.handleSagaFailure(orderId, "INVALID_PRICE")`로 기존 보상(FAILED + 재고·유저마킹 복구) 재사용.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.service.OrderCommandServiceTest"`
- 롤백 방법: `git checkout --` 해당 파일 + 신규 DTO 삭제
- 예상 소요: 보통

### 단계 4: serverC DB 마이그레이션 + 결제 금액 저장 ⚠️ 단계별 승인
- 변경 파일: `serverC/src/main/resources/db/migration/V2__add_payment_amount.sql`(신규), `entity/Payment.java`, `dto/PurchaseMessage.java`, `repository/PaymentRepository.java`, `PaymentRepositoryTest.java`
- 변경 내용 요약: `payments.amount BIGINT NULL`, `payments.currency VARCHAR(3) NULL` 추가. `PurchaseMessage`에 `unitPrice`·`totalAmount`·`currency` 필드 추가(옛 메시지는 null). `claimOrder`가 금액·통화를 함께 INSERT.
- 검증 방법: `/db-migration` 검토 + `.\gradlew.bat :serverC:test --tests "promotion.serverC.repository.PaymentRepositoryTest"` (Testcontainers — Docker가 떠 있으면 직접, 없으면 사용자 요청)
- 롤백 방법: 마이그레이션 파일 삭제 + `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 5: serverC PG 승인 금액 검증
- 변경 파일: `service/PgClient.java`, `service/MockPgClient.java`, `service/PgApprovalResult.java`(신규), `service/PaymentService.java`, `PaymentServiceTest.java`, (`MockPgClient` 테스트 있으면 수정)
- 변경 내용 요약: `processPayment()`가 `boolean` 대신 `PgApprovalResult(approved, approvedAmount)`를 반환. 애매한 응답 후 조회 경로(`queryTransaction`)도 승인 금액을 돌려주도록 Mock 거래 저장소에 금액 보관. `PaymentService`: 금액 있으면 승인 금액과 비교 → 불일치 시 `pgClient.cancelPayments` + FAILED + `payment-result(false, "금액 불일치")` + `business_payment_error_total{type=amount_mismatch}`. 금액 없으면 기존 처리 + `business_payment_legacy_without_amount_total` 경고 메트릭.
- 검증 방법: `.\gradlew.bat :serverC:test --tests "promotion.serverC.service.PaymentServiceTest"` (+ 수정한 Mock/컨슈머 테스트)
- 롤백 방법: `git checkout --` 해당 파일 + 신규 클래스 삭제
- 예상 소요: 보통

### 단계 6: 프론트 가격 표시
- 변경 파일: `frontend/src/api/generated/server-a-types.ts`(재생성), `frontend/src/app/purchase/[goodsId]/page.tsx`, `PurchaseForm.tsx`, 대응 `*.test.tsx`, (E2E 스펙)
- 변경 내용 요약: `npm run generate:api:a`로 타입 재생성. 상품 화면에 단가, 구매 폼에 `단가 × 수량` 예상 금액을 원화 형식으로 표시. 구매 요청 본문에는 금액을 넣지 않음.
- 검증 방법: `npm run test` (Vitest 전체), `npm run test:e2e` (백엔드/Prism 환경이 떠 있으면 직접, 없으면 사용자 요청), `npm run lint`
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 보통

### 단계 7: 문서
- 변경 파일: `docs/arch-snapshot.md`, `docs/purchase-saga-flow.md`, `docs/design-notes.md`(조건 충족 시)
- 변경 내용 요약: 엔티티 금액 필드, `payment-request` 메시지 변경, 금액 검증·옛 메시지 처리, 0원 차단 흐름 반영.
- 검증 방법: 육안 확인
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: 배포 중 옛/새 메시지 혼재 → 대응: 필드 추가만(JSON 하위 호환), serverC는 금액 없는 메시지를 기존 방식으로 처리. 권장 배포 순서는 serverC → serverA
- 리스크: `PgClient` 반환 타입 변경으로 컴파일 영향 범위 확대 → 대응: 사용처(`PaymentService`, Mock, 테스트)만 수정, 단계 5 안에서 컴파일·테스트로 확인
- 리스크: 0원 차단 시 같은 트랜잭션 안에서 `handleSagaFailure` 호출 → 대응: 테스트로 FAILED·보상·결제 요청 미발행을 검증
- 리스크: 기존 상품이 0원이 되어 로컬 데모 구매가 실패 → 대응: 마이그레이션 적용 후 로컬 상품 가격을 사용자가 입력하도록 안내
- 리스크: E2E 환경(백엔드 기동) 필요 → 대응: 환경이 없으면 사용자에게 실행 요청

## 의존성
- Flyway 마이그레이션 (serverA V3, serverC V2) — 실제 DB 적용은 사용자
- serverA 기존 보상 흐름 `SagaOrchestratorService.handleSagaFailure`
- `contracts/server-a-openapi.yaml` CI drift-check
- 신규 Gradle 의존성 없음
