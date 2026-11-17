# 백엔드 E2E 시나리오

떠 있는 백엔드 전체(serverA·B·C, Kafka, Debezium CDC)에 HTTP로 요청해 최종 결과를 확인한다. 프론트·브라우저는 필요 없다.
이 문서는 **도구와 무관한 명세**다. 지금은 Playwright(`request`)로 구현돼 있고, REST Assured 등으로 옮길 때는 이 문서를 기준으로 다시 만든다.

## 실행
1. 백엔드 환경 기동 (MySQL 3·Redis 2·Kafka·Kafka Connect·Eureka·serverA/B/C·user-service, Debezium 커넥터 등록)
2. `cd backend-e2e && npm ci && npm test`
3. 결과 보기: `npm run report` (단계별 진행, 첨부된 응답 JSON, trace 뷰어로 요청·응답 확인)

대상 주소·대기 시간은 환경변수로 바꾼다: `E2E_SERVER_A_URL`(기본 `http://localhost:8080`), `E2E_SERVER_B_URL`(8081), `E2E_SERVER_C_URL`(8082), `E2E_USER_SERVICE_URL`(8086), `E2E_KAFKA_CONNECT_URL`(8083), `E2E_FLOW_TIMEOUT_MS`(60000). 사전 점검 생략: `E2E_SKIP_PREFLIGHT=1`.

## 사전 점검 (모든 시나리오 전에)
실패하면 시나리오를 실행하지 않고 원인을 보고한다 (환경 문제 — 코드를 고치지 않는다).
- serverA·B·C·user-service `GET /actuator/health` → `status: UP` (DB·Redis 상태 포함)
- Kafka Connect `GET /connectors/{name}/status` → `promotion-outbox-connector`, `payment-outbox-connector`의 connector·모든 task가 `RUNNING`
- 각 점검은 타임아웃 10초, 실패 시 1회 재확인

## 시나리오 1: 구매 흐름 (`purchase-flow.spec.ts`)

| 단계 | 요청 | 기대 결과 |
|---|---|---|
| ① 상품 생성 | serverA `POST /api/v1/goods` `{name: 고유값, price: 15000, stock: 5}` | 200, `price` = 15000 |
| ② 구매 2개 | serverA `POST /api/v1/promotions/purchase` `{userId: 실행마다 고유, goodsId, quantity: 2, ...}` | **202**, `orderId` 반환 |
| ③ 결제 확인 | serverC `GET /api/v1/payments/{orderId}`를 최대 60초 폴링 | `status: PAID`, **`amount: 30000`**, `currency: KRW`, `quantity: 2` |
| ④ 주문 상태 확인 | serverB `GET /api/v1/orders/{orderId}/status`를 최대 60초 폴링 | `status: PAID` |

- 금액은 클라이언트가 보내지 않는다. 서버가 단가 × 수량으로 계산한 값이 결제 승인 금액과 같아야 한다.
- 모의 PG는 1% 확률로 결제를 거절하므로(정상 동작) 실패 시 1회 재시도한다. 재시도마다 상품·사용자를 새로 만든다.
- 각 단계의 응답을 결과 리포트에 첨부한다 (결과 보고 근거).

## 시나리오 2: PG 취소 실패 관리자 API (`payment-cancel-admin.spec.ts`)

| 단계 | 요청 | 기대 결과 |
|---|---|---|
| ① 역할 없음 | serverC `GET /api/v1/payments/admin/cancel-failures` (헤더 없음) | **403** |
| ② 일반 사용자 | 같은 요청, `X-User-Role: ROLE_USER` | **403** |
| ③ 관리자 목록 | `GET ...?status=EXHAUSTED`, `X-User-Role: ROLE_ADMIN` | 200, 배열 |
| ④ 없는 주문 재처리 | `POST .../{없는 orderId}/retry`, `X-User-Role: ROLE_ADMIN` | **404** |

- `X-User-Role`은 운영에서 게이트웨이가 JWT로 검증해 넣는다. 여기서는 serverC를 직접 호출해 역할 검사만 확인한다.
- PG 취소 실패 → 자동 재시도 → 소진 흐름은 실패를 일부러 만들 수단이 없어 serverC 통합 테스트(`PaymentCancelRetryIntegrationTest`)가 담당한다. 사용자 환불은 시나리오 3.

## 시나리오 3: 사용자 환불 (`refund-flow.spec.ts`)
명세: `docs/specs/user-refund.md` (S-1·S-5·S-6·P-5·P-12)

| 단계 | 요청 | 기대 결과 |
|---|---|---|
| ① 사용자 생성 | user-service `POST /internal/api/users` (실행마다 고유 email·전화번호) | **201**, `id`(숫자)·`userId`(로그인 ID) |
| ② 상품 생성 | serverA `POST /api/v1/goods` `{price: 15000, stock: 5}` | 200 |
| ③ 구매 2개 | serverA `POST /api/v1/promotions/purchase` `{userId: ①의 id, quantity: 2, ...}` | 202, `orderId` |
| ④ 결제 완료 | serverB `GET /api/v1/orders/{orderId}/status` 최대 60초 폴링 | `status: PAID` |
| ⑤ 구매 후 재고 | serverA `GET /api/v1/goods/{goodsId}` | `stock: 3` |
| ⑥ 없는 로그인 ID 환불 | serverA `POST /api/v1/orders/{orderId}/refund`, `X-User-Id: 없는 값` | **404** `ORDER_NOT_FOUND` |
| ⑦ 환불 동시 2회 | 같은 요청을 `X-User-Id: ①의 userId`로 동시에 2번 | **둘 다 200**, `status`는 `REFUND_REQUESTED` 또는 `REFUNDED` |
| ⑧ 환불 완료 | serverB 주문 상태 최대 60초 폴링 | `status: REFUNDED` |
| ⑨ 재고 복구 | serverA 상품 조회 | **`stock: 5`** (정확히 1회 복구) |
| ⑩ 재구매 | ③과 같은 구매 요청 | **400** `DUPLICATE_ORDER` (환불해도 구매 표시 유지) |

- `X-User-Id`는 운영에서 게이트웨이가 JWT로 검증해 넣는다. 여기서는 serverA를 직접 호출한다.
- "중복 요청에도 PG 취소 1회"는 블랙박스로 볼 수 없어 재고가 정확히 1회 복구됐는지(⑨)로 확인한다. PG 취소 멱등 자체는 serverC 단위 테스트가 담당한다.
- 모의 PG 거절(1%) 대비 1회 재시도, 재시도마다 사용자·상품을 새로 만든다.

## 시나리오 4: PG 대사 수동 실행 관리자 API (`reconciliation-admin.spec.ts`)
명세: `docs/specs/pg-reconciliation-batch.md` (P-8·P-9·S-10, "API")

| 단계 | 요청 | 기대 결과 |
|---|---|---|
| ① 관리자 아님 | serverC `POST /api/v1/payments/admin/reconciliations?date={과거 날짜}` (헤더 없음 / `ROLE_USER`) | **403** |
| ② 오늘 날짜 | 같은 요청, `date=오늘(KST)`, `X-User-Role: ROLE_ADMIN` | **400** |
| ③ 첫 실행 | `date=실행마다 무작위 과거 날짜(PG 파일 없음)`, 관리자 | **202**, `mode: NEW`, `jobExecutionId` |
| ④ 실패 확인 | serverC `GET /actuator/prometheus` 최대 60초 폴링 | `business_reconciliation_job_failed_total`이 ③ 전보다 **+1** |
| ⑤ 다시 실행 | ③과 같은 요청 | **202**, `mode: RESTART`, 더 큰 `jobExecutionId` |
| ⑥ 재시작 실패 확인 | ④와 같이 폴링 | ③ 전보다 **+2** |

- 실행이 비동기라 결과는 serverC가 노출한 메트릭으로 확인한다 (실행 상태 조회 API는 명세 범위 밖).
- PG 파일을 컨테이너에 넣을 수단이 없어 성공·불일치 경로와 409(실행 중)는 serverC 테스트(`ReconciliationMetricsTest`·`ReconciliationRunServiceTest` 등)가 담당한다.
