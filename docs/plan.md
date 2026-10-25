# 계획서: 구매 플로우 프론트엔드 구현 (serverA 연동, 첫 mutation 화면)

- 작성일: 2026-10-25
- 관련 이슈/티켓: 없음
- 브랜치: feature/purchase-flow

## 목표
serverA의 구매 기능(`POST /api/v1/promotions/purchase`)과 상품 조회(`GET /api/v1/goods/{goodsId}`)를 프론트엔드에 구현한다. 학습 목적이라 인증은 제외하고(userId 하드코딩), 프로젝트의 첫 실제 "조작(mutation)" 화면으로서 contract-first 멀티 백엔드 구조·CORS·MSW 패턴을 실제로 검증하는 데 집중한다.

## 성공 기준
- [ ] `npx tsc --noEmit`, `npm run build` 통과
- [ ] 로컬에서 serverA(+필요 인프라) 기동 후 상품 조회 → 구매 → `/orders/{orderId}` 상태 페이지까지 실제 흐름이 끝까지 동작 (스크린샷으로 확인)
- [ ] 브라우저가 serverA를 same-origin 프록시(`/api-a/*`)로 호출함을 확인 — CORS 에러 없음
- [ ] Vitest(PurchaseForm 단위) + MSW(구매 요청 모킹) + Playwright E2E(상품조회→구매→상태 흐름, axe-core) 작성, 환경 되는 범위는 Claude가 직접 실행해 통과 확인
- [ ] serverA에 계약 드리프트 테스트 추가, `compileTestJava` 통과

## 비범위 (Out of Scope)
- 로그인/인증 시스템, userId 동적 처리 (계속 하드코딩)
- `clientIp`를 실제 요청에서 유도하도록 백엔드 계약을 바꾸는 것 (더미값 유지)
- 결제수단 값 검증/실제 결제 연동
- `.github/workflows/ci-cd.yaml`에 프론트 E2E job 추가
- 재고 0일 때 버튼 비활성화 등 세밀한 재고 UX (서버 에러 메시지로만 표시)
- 기존 `client.ts`(serverB)·`generate:api`(serverB) 이름 변경 — 대칭성보다 기존 동작 코드 안 건드리는 쪽을 우선

## 단계별 작업 계획

### 단계 1: serverA 계약 작성 + 타입 생성
- 변경 파일: `contracts/server-a-openapi.yaml`(신규), `frontend/package.json`(`generate:api:a` 스크립트 추가), `frontend/src/api/generated/server-a-types.ts`(생성물)
- 변경 내용 요약: `POST /api/v1/promotions/purchase`(`PurchaseRequest`/`PurchaseResponse`), `GET /api/v1/goods/{goodsId}`(`GoodsResponse`) 계약을 serverA의 실제 DTO(`PurchaseRequest`, `PurchaseResponse`, `GoodsResponse`)를 보고 작성. 기존 `contracts/openapi.yaml`(serverB)과 별개 파일로 분리.
- 검증 방법: `npm run generate:api:a` 실행 후 `server-a-types.ts` 생성 확인, `tsc --noEmit` 통과
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 보통

### 단계 2: client-a.ts + CORS 프록시
- 변경 파일: `frontend/src/lib/api/client-a.ts`(신규), `frontend/next.config.ts`(rewrites 추가)
- 변경 내용 요약: serverA용 openapi-fetch 클라이언트. Server Component(Node)에서는 `NEXT_PUBLIC_SERVER_A_BASE_URL`(기본값 `http://localhost:8080`)로 직접 호출(서버-서버라 CORS 무관), Client Component(브라우저)에서는 `/api-a`로 상대경로 호출하도록 `typeof window` 분기. `next.config.ts`에 `/api-a/:path*` → `http://localhost:8080/:path*` rewrites 추가해 브라우저 요청이 same-origin으로 보이게 함(serverA 코드는 안 건드림).
- 검증 방법: `tsc --noEmit` 통과
- 롤백 방법: 신규 파일 삭제, next.config.ts rewrites 블록 제거
- 예상 소요: 보통

### 단계 3: 상품 조회 화면 (`/purchase`, `/purchase/[goodsId]`)
- 변경 파일: `frontend/src/app/purchase/page.tsx`(신규, Client Component — goodsId 입력 폼), `frontend/src/app/purchase/[goodsId]/page.tsx`(신규, Server Component — 상품명/재고 표시, Suspense+에러 처리, 기존 `orders/[orderId]` 패턴과 동일)
- 변경 내용 요약: 기존 홈 화면(주문조회) 검색 폼과 동일한 패턴 재사용 — goodsId 입력 → `router.push('/purchase/{id}')` → Server Component가 `clientA.GET`으로 상품 조회.
- 검증 방법: `npm run build` 통과, 로컬에서 실제 조회 확인(단계 5에서 종합 확인)
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 보통

### 단계 4: 구매 폼 구현
- 변경 파일: `frontend/src/app/purchase/[goodsId]/page.tsx`(구매 폼 Client Component 추가 — 같은 파일 내 또는 별도 컴포넌트 파일)
- 변경 내용 요약: quantity/paymentMethod/shippingAddress/zipCode/phoneNumber/email/deliveryMemo 입력 필드(필드 수가 많지만 각각 단순 필수값이라 RHF+Zod 대신 단일 state 객체+`useState`로 처리). `userId`는 `1`, `clientIp`는 `"127.0.0.1"` 하드코딩. 제출 시 `clientA.POST('/api/v1/promotions/purchase', ...)` 호출 → 202 성공 시 `router.push('/orders/{orderId}')`, 실패(네트워크/4xx) 시 에러 메시지 표시(기존 `orders/[orderId]`에서 고친 try/catch 패턴 재사용).
- 검증 방법: `tsc --noEmit`, `npm run build` 통과
- 롤백 방법: 추가한 컴포넌트/핸들러 제거
- 예상 소요: 김

### 단계 5: 로컬 E2E 검증
- 변경 파일: 없음 (검증만)
- 변경 내용 요약: serverA(+Redis 등 필요 인프라) 로컬 기동 후 실제 상품 조회→구매→`/orders/{orderId}` 흐름을 Playwright MCP 스크린샷으로 끝까지 확인. CORS 프록시가 실제로 동작하는지(브라우저가 8080을 직접 안 부르는지) 네트워크 요청 경로로 확인.
- 검증 방법: 스크린샷 + 네트워크 요청 확인
- 롤백 방법: 해당 없음
- 예상 소요: 보통

### 단계 6: 테스트 작성
- 변경 파일: `frontend/src/mocks/handlers.ts`(신규 — 프로젝트 첫 MSW 핸들러, 구매 POST 모킹, `satisfies` 타입단언), `frontend/src/app/purchase/page.test.tsx` 또는 유사(Vitest+RTL), `frontend/e2e/purchase.spec.ts`(신규, Playwright — 상품조회→구매→상태 흐름 + axe-core)
- 변경 내용 요약: `frontend-testing` skill 컨벤션대로 Client Component 단위+MSW, Server Component는 E2E로 검증. 시각 회귀는 기존 1~2개 제한 원칙에 따라 이번엔 추가하지 않음(이미 `order-status.png` 하나 있음).
- 검증 방법: 빠른 테스트(Vitest)는 Claude가 직접 실행해 통과 확인. Playwright E2E는 로컬 환경(serverA 등)이 떠 있으면 Claude가 직접, 아니면 사용자에게 요청 (새 테스트 분업 원칙)
- 롤백 방법: 신규 테스트 파일 삭제
- 예상 소요: 보통

### 단계 7: serverA 계약 드리프트 테스트
- 변경 파일: `serverA/src/test/java/.../contract/OpenApiContractTest.java`(신규, serverB의 패턴 재사용)
- 변경 내용 요약: serverA의 `/v3/api-docs`와 `contracts/server-a-openapi.yaml`을 비교해 드리프트를 잡는 JUnit 테스트. 기존 `./gradlew clean test` CI job에 자연히 포함됨(워크플로 수정 불필요).
- 검증 방법: `compileTestJava` 통과 확인(테스트 자체 실행은 환경 있으면 직접, 커밋 전 최종 확인은 사용자 `gradlew test`)
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: serverA 구매가 비동기 Saga라 202 받은 직후 `/orders/{orderId}` 상태가 아직 PENDING도 아닐 수 있음(파이프라인 처리 전) → 대응: 상태 페이지는 이미 "조회 중...에러...표시" 분기가 있으니 그대로 재사용, 별도 폴링은 비범위로 둠
- 리스크 2: serverA 로컬 기동에 필요한 인프라(Redis/Kafka/MySQL)가 많아 E2E 검증이 무거울 수 있음 → 대응: 꼭 필요한 것만(Redis 등) 최소 구성으로 기동

## 의존성
- 신규 npm 의존성 없음(기존 openapi-typescript/openapi-fetch 재사용)
- 신규 Gradle 의존성 없음(serverB와 동일하게 snakeyaml 재사용)
