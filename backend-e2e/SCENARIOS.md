# 백엔드 E2E 시나리오

떠 있는 백엔드 전체(serverA·B·C, Kafka, Debezium CDC)에 HTTP로 요청해 최종 결과를 확인한다. 프론트·브라우저는 필요 없다.
이 문서는 **도구와 무관한 명세**다. 지금은 Playwright(`request`)로 구현돼 있고, REST Assured 등으로 옮길 때는 이 문서를 기준으로 다시 만든다.

## 실행
1. 백엔드 환경 기동 (MySQL 2·Redis 2·Kafka·Kafka Connect·Eureka·serverA/B/C, Debezium 커넥터 등록)
2. `cd backend-e2e && npm ci && npm test`
3. 결과 보기: `npm run report` (단계별 진행, 첨부된 응답 JSON, trace 뷰어로 요청·응답 확인)

대상 주소·대기 시간은 환경변수로 바꾼다: `E2E_SERVER_A_URL`(기본 `http://localhost:8080`), `E2E_SERVER_B_URL`(8081), `E2E_SERVER_C_URL`(8082), `E2E_KAFKA_CONNECT_URL`(8083), `E2E_FLOW_TIMEOUT_MS`(60000). 사전 점검 생략: `E2E_SKIP_PREFLIGHT=1`.

## 사전 점검 (모든 시나리오 전에)
실패하면 시나리오를 실행하지 않고 원인을 보고한다 (환경 문제 — 코드를 고치지 않는다).
- serverA·B·C `GET /actuator/health` → `status: UP` (DB·Redis 상태 포함)
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
