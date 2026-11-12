# 체크리스트: 결제 금액 기본기

- 마지막 업데이트: 2026-11-12

## [완료] 결제 금액 기본기

### 진행 상황
- [x] 단계 1: serverA V3 마이그레이션 + Goods/Order 금액 필드 ⚠️ 단계별 승인
  - [x] 검증 통과 (`gradlew :serverA:compileJava :serverA:compileTestJava` BUILD SUCCESSFUL + `/db-migration` 🟡 MEDIUM, AFTER 제거 반영)
  - [x] GoodsRepositoryTest 4 + GoodsRepositoryConcurrencyTest 2 통과 (Testcontainers)
  - [x] 로컬 DB V3 적용 (사용자 요청, server-a 재기동 시 Flyway — `flyway_schema_history` v3 success, ddl-auto validate 통과)
- [x] 단계 2: serverA 상품 API 가격 + 계약
  - [x] 검증 통과 (`gradlew :serverA:test --tests GoodsControllerTest` — 5건 통과)
- [x] 단계 3: serverA 주문 금액 스냅샷 + PaymentRequestMessage + 0원 차단
  - [x] 검증 통과 (`gradlew :serverA:test --tests OrderCommandServiceTest` — 6건 통과)
- [x] 단계 4: serverC V2 마이그레이션 + 결제 금액 저장 ⚠️ 단계별 승인
  - [x] 검증 통과 (`/db-migration` 🟢 LOW + PaymentRepositoryTest 4 / PaymentServiceTest 9 / PaymentKafkaConsumerTest 1 통과)
  - [x] 로컬 DB V2 적용 (사용자 요청, server-c 재기동 — `flyway_schema_history` v2 success, ddl-auto validate 통과)
- [x] 단계 5: serverC PG 승인 금액 검증 + 옛 메시지 처리
  - [x] 검증 통과 (PaymentServiceTest 13 / PaymentKafkaConsumerTest 1 / PaymentRepositoryTest 4 통과)
  - 계획 대비 변경: `PgApprovalResult` 대신 `PgTransactionResult(status, approvedAmount)` 하나로 승인·조회 응답 통일 (기존 `PgTransactionStatus` 재사용)
- [x] 단계 6: 프론트 가격·예상 금액 표시
  - [x] 검증 통과 (Vitest 15/15, `tsc --noEmit`·`next build` 통과, ESLint 변경 파일 0건, E2E headed 6/7 + 시각 회귀 headless 1/1)
- [x] 단계 7: 문서 (arch-snapshot, purchase-saga-flow, design-notes)

### 최종 검증
- [x] 수정·추가한 백엔드 테스트 모두 통과
- [x] 프론트 Vitest 전체 통과 + E2E (P0)
- [x] `gradlew build` — serverC 통과 / serverA 67/69 (실패 2건 `OpenApiContractTest`는 main에서도 실패하는 기존 결함 → 후속 작업)
- [x] 서버 간 흐름 (Kafka Connect + Debezium): orders·payments 30,000 KRW PAID, serverB PAID
- [x] 신규 Gradle 의존성 없음
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (Debezium DB명 수정은 사용자 승인 후 별도 커밋)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

### 후속 작업 (같은 브랜치, 각각 /plan)
- [ ] 2번: 결제조회 UNKNOWN → EXPIRED 시 망취소 누락 (#20 안전망 공백)
- [ ] 1번: serverA springdoc 추가 + `OpenApiContractTest` 통과 (새 의존성 — 승인 필요)
- [ ] 1·2번 완료 후 전체 E2E(화면 + 서버 간 흐름) 재확인 → PR

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 마이그레이션 적용 후 로컬 기존 상품 가격 입력 필요 (0원이면 구매 실패)
- 금액 필수화(확장-축소 마지막 단계)는 후속 작업
- `frontend/e2e/purchase.spec.ts`가 가격 없이 상품을 생성 → 단계 2 이후 400으로 E2E 실패. 단계 6에서 시드에 `price` 추가
- 로컬 상품 15개 가격 15,000원 입력 완료 (사용자 요청)
- ESLint `react-hooks/error-boundaries` 3건이 `frontend/src/app/orders/[orderId]/page.tsx`에 기존부터 존재 (비범위, 미수정)
- 시각 회귀 테스트는 headed 모드에서 렌더링 차이로 실패, headless에서는 통과 — 시각 회귀는 headless로 실행할 것
- [수정함·사용자 승인] Debezium serverA 커넥터가 존재하지 않는 `promotion` DB를 감시 → `debezium/outbox-connector.json`, `helm/promotion-infra/templates/debezium/init-job.yaml`을 `order`로 수정 (helm template 확인). 별도 커밋
- `values-local.yaml`(로컬 kind) serverA 데이터소스가 `/promotion` DB를 가리킴 — 실제 DB는 `order` (비범위, 미수정)
- `OpenApiContractTest`가 CI 조건(H2 URL)에서 `driver-class-name: com.mysql.cj.jdbc.Driver` 고정 때문에 컨텍스트 로드 실패 — main에서도 동일 실패 확인(worktree). 로컬 MySQL 연결로는 별도 실행
- #20 안전망: 결제조회 UNKNOWN(serverC 인스턴스 미발견) → EXPIRED 폴백 시 Saga 상태에 결제완료 플래그가 없으면 망취소가 발행되지 않음 — serverC PAID인데 주문 EXPIRED 사례 재현(order 01a1ce44…). 기존 설계 공백 (비범위, 미수정)
- 로컬 Kafka Connect 커넥터 오프셋 초기화(serverA: DB 변경, serverC: binlog 위치 불일치 1236) — 로컬 인프라 상태 조치, 코드 변경 아님
- 서버 간 흐름 확인: order 01a1ce47… orders 30,000 KRW PAID / payments 30,000 KRW PAID / serverB PAID
- `MockPgClient`는 랜덤(Math.random) 기반이라 단위 테스트 미작성 — 금액 반환 로직은 PaymentServiceTest에서 PgClient mock으로 검증
- 금액 불일치 망취소(`cancelPayments`)가 결제 트랜잭션 안에서 호출됨 — 기존 PG 승인 호출과 같은 패턴 유지(트랜잭션 밖 분리는 비범위)
