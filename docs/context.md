# 맥락 노트: 결제 금액 기본기

## 왜 이 방식을 선택했는가
결제 시스템인데 금액이 전혀 모델링되어 있지 않았다(`Goods`·`Order`·`Payment`·메시지 어디에도 price/amount 없음). 2단계 Spring Batch 정산을 하려면 정산할 금액 자체가 필요하고, "서버 금액 계산 + PG 승인 금액 검증"은 결제 도메인의 기본 방어선이다.

사용자 결정 (2026-11-12):
1. **가격 조회 시점 = 주문 저장 단계(Phase 1)** — 구매 접수(`acceptPurchase`)는 Redis만 거치는 TPS 핵심 설계라 건드리지 않는다. 프로모션 중 가격은 고정이므로 접수~소비 사이 몇 초 차이는 허용.
2. **금액 없는 옛 메시지 = 기존 방식 처리 + 경고 메트릭** — 확장-축소(expand-contract) 패턴 1단계. 배포 순서 serverC → serverA. 금액 필수화는 후속.
3. **PG 승인 금액 불일치 = serverC 즉시 망취소 + FAILED** — serverA는 기존 보상 흐름(재고·유저마킹 복구)을 그대로 탄다. serverA의 `payment-cancel`은 "결제 성공했던 경우"에만 발행되므로 불일치 건의 망취소는 serverC가 직접 해야 한다.
4. **기존 상품 가격 = DEFAULT 0 + 0원 상품 구매 차단** — 0원 결제가 PG로 나가지 않게 주문 저장 단계에서 기존 `handleSagaFailure`로 실패 처리.

금액 타입은 원화 `long`(원 단위) + DB `BIGINT`, 통화는 `VARCHAR(3)`에 `KRW`. 원화는 소수가 없어 정수가 가장 단순·안전하다. BigDecimal은 수수료율 계산이 생기는 2단계에서 쓴다.

## 검토했으나 채택하지 않은 대안
### 대안 A: `PurchaseMessage`에 금액 필드 추가해 두 토픽 공용
- 왜 안 썼나: 구매 접수 시점엔 금액을 모르므로 `purchase_events`에선 항상 null인 필드가 생긴다. `payment-request` 전용 `PaymentRequestMessage`로 분리하면 의미가 명확하다 (JSON 필드명은 serverC `PurchaseMessage`와 호환).

### 대안 B: 구매 접수 시점에 가격 조회
- 왜 안 썼나: 접수 경로에 캐시/DB 조회가 추가되어 TPS 설계가 바뀜 (사용자 결정 1).

### 대안 C: 금액 불일치 시 FAILED만 기록
- 왜 안 썼나: 고객 돈이 승인된 채 묶임. 수동 처리 의존 (사용자 결정 3).

### 대안 D: 금액 BigDecimal / DECIMAL
- 왜 안 썼나: 원화는 소수가 없어 정수로 충분. 다중 통화는 비범위.

## 기존 코드베이스 컨벤션
- 엔티티: `@Getter` + `@Builder` 생성자 + 도메인 메서드, `@Setter` 금지 (`serverA/.../entity/Order.java`)
- serverC 결제 저장: JPA가 아닌 `JdbcTemplate` (`serverC/.../repository/PaymentRepository.java`)
- 메시지: record DTO, Spring Boot `ObjectMapper` 주입(모르는 필드 무시) — 단 serverB는 `new ObjectMapper()`라 모르는 필드에 실패(이번 변경 토픽은 serverB가 소비하지 않음)
- 마이그레이션: Flyway `db/migration/V{n}__*.sql`, 컬럼 변경은 ALTER (DROP+ADD 금지)
- 메트릭: `business_payment_*` 카운터 (`PaymentService.initMetrics`)
- 테스트: `OrderCommandServiceTest`, `PaymentServiceTest`(Mockito), `PaymentRepositoryTest`(Testcontainers), `GoodsControllerTest`(@WebMvcTest)

## 관련 파일/위치
- `serverA/.../service/OrderCommandService.java` — Phase 1 주문 저장, `payment-request` Outbox
- `serverA/.../service/SagaOrchestratorService.java` — `handleSagaFailure` 보상 재사용
- `serverA/.../service/GoodsService.java`, `dto/request/CreateGoodsRequest.java`, `dto/response/GoodsResponse.java`
- `serverC/.../service/PaymentService.java` — 승인·애매응답 처리
- `serverC/.../service/PgClient.java`, `MockPgClient.java`
- `serverC/.../repository/PaymentRepository.java` — `claimOrder`
- `contracts/server-a-openapi.yaml` — 프론트 계약 원본
- `frontend/src/app/purchase/[goodsId]/` — 구매 화면

## 외부 참조
- 토스페이먼츠 결제 승인 API: orderId + amount를 함께 보내 금액 위변조를 막는 구조 (개념 참고)
