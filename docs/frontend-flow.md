# frontend 흐름

React 19 + TypeScript + Vite SPA. 화면은 `OrderStatusPage` 단 하나. orval이 OpenAPI 스펙으로부터 생성한 TanStack Query 훅으로 serverB를 호출한다.

## 진입점

| 진입점 | 역할 |
|---|---|
| `main.tsx` | `QueryClientProvider` + `BrowserRouter`로 앱 부트스트랩 |
| `App.tsx` | `react-router-dom` `Routes` — `/` 경로 하나만 `OrderStatusPage`에 매핑 |

## 1. `OrderStatusPage.tsx` — 실제 동작

1. `orderId`(입력 중인 값)와 `searchedOrderId`(조회 확정된 값) 두 state로 분리 — 입력 중에는 쿼리가 실행되지 않는다.
2. "조회" 버튼 클릭(`onClick`) 시 `setSearchedOrderId(orderId)` — 이 시점에 비로소 `useGetOrderStatus(searchedOrderId, { query: { enabled: searchedOrderId !== '' } })`가 활성화되어 **1회성 GET 요청**을 보낸다.
3. `axiosInstance` (`frontend/src/api/axios-instance.ts`) — `baseURL: '/'`로 상대경로 호출(개발 서버 프록시 또는 배포 시 동일 오리진 gateway를 전제).
4. `useGetOrderStatus`는 `frontend/src/api/generated/order-query-controller/order-query-controller.ts`(orval 자동 생성, 수동 편집 금지 주석 있음)의 TanStack Query 훅 — `GET /api/v1/orders/{orderId}/status` 호출, 응답은 `OrderStatusResponse`(`{orderId, status}`).
5. 렌더링: `isFetching`이면 "조회 중...", `isError`면 에러 문구, `data` 있으면 `주문 {orderId}의 상태: {status}`를 **가공 없이 그대로** 표시(PAID/FAILED/EXPIRED/PENDING/NOT_FOUND를 분기 처리하지 않음).

## 2. ⚠️ README.md의 폴링 정책과 실제 코드가 일치하지 않음

`README.md`의 Phase 3 시퀀스 다이어그램에는 다음과 같은 상세 폴링 정책이 서술되어 있다:

> serverB GET .../status 폴링 · 초기 대기 2s · Exponential Backoff + Jitter: 2→4→8→16→32s(최대 6회) · NOT_FOUND/PENDING → 처리 중 유지 · PAID → 성공 종료 / FAILED·EXPIRED → 실패 종료 · 60s 내 미확정 시 폴링 중단

그러나 **`frontend/src/pages/OrderStatusPage.tsx`에는 이 로직이 전혀 없다.** `useGetOrderStatus`에 `refetchInterval`, 재시도 간격, 타임아웃, 상태별 분기 등 어떤 형태의 폴링 코드도 존재하지 않으며, 버튼 클릭 1회당 요청 1회만 발생하는 단순 조회 UI다. `load-test.js`/`load-test2.js`(k6 부하테스트 스크립트)에서도 해당 백오프 로직을 찾지 못했다.

이 문서는 실제 코드(정적 조회 UI)를 있는 그대로 기술한다. README의 폴링 정책 서술은 설계 의도이거나 과거 구현/향후 계획일 수 있으나, 현재 프론트엔드 코드베이스에는 반영되어 있지 않다는 점을 기록해둔다. (이번 작업 범위는 문서화이므로 README 수정이나 프론트엔드 구현 추가는 하지 않았다.)

## 3. 테스트 — `OrderStatusPage.test.tsx`

MSW(`mocks/handlers.ts`)로 `GET /api/v1/orders/:orderId/status`를 모킹: `orderId === 'order-123'`이면 `SHIPPED`, 그 외에는 `NOT_FOUND`. 테스트 3종: 초기 렌더링, 정상 조회, 존재하지 않는 주문 ID 조회 — 모두 위에서 설명한 "버튼 클릭 → 1회 요청 → 결과 표시" 흐름만 검증한다(폴링 관련 테스트 없음).
