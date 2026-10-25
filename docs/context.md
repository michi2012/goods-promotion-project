# 맥락 노트: 구매 플로우 프론트엔드 구현 (serverA 연동, 첫 mutation 화면)

## 왜 이 방식을 선택했는가
지금까지 프론트는 serverB의 주문 상태 "조회" 화면 하나뿐이라, 조작(mutation)·CORS·MSW가 전부 미뤄진 상태였다. 사용자가 "serverA 구매 기능도 프론트로 구현해야 하지 않냐"고 제안했고, 학습 목적이라 인증은 빼고 구매 플로우 자체(첫 mutation 패턴 검증)에 집중하기로 합의했다.
- `userId`는 로그인 없이 하드코딩(`1`), `clientIp`도 더미값(`"127.0.0.1"`) — 실제로 바꾸면 안 되는 값이지만 백엔드 계약이 필수 필드라 값은 채워야 함.
- 계약/클라이언트를 serverA/serverB로 분리하기로 함 — 포트와 도메인이 다른 서버를 하나의 `servers` 블록에 억지로 합치면 이후 다른 백엔드(serverC 등) 추가 시 계속 꼬이기 때문.
- CORS는 serverA 코드를 건드리지 않고 Next.js `rewrites`로 해결 — Server Component(Node)는 서버-서버 호출이라 애초에 CORS 영향이 없고, Client Component(브라우저)만 프록시를 거치게 했다.
- 상품 조회 화면을 포함하기로 함(사용자가 "넣어달라"고 명시) — 구매 전에 상품명/재고를 보여주는 게 자연스러운 흐름이고, 기존 `orders/[orderId]`와 동일한 "검색 폼 → 동적 라우트 Server Component" 패턴을 그대로 재사용할 수 있어 구조적으로 깔끔하다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 계약/클라이언트를 기존 serverB 파일에 합침
- 무엇: `contracts/openapi.yaml`, `client.ts` 하나에 serverA 경로/클라이언트도 추가
- 왜 안 썼나: `servers` 블록에 포트가 다른 두 서비스가 섞여 계약 파일이 "어느 서버 스펙인지" 모호해짐. 서버별 분리가 더 명확하다고 판단.

### 대안 B: 상품 조회 화면 생략, goodsId도 구매 폼의 단순 입력 필드로만 처리
- 무엇: `GET /api/v1/goods/{goodsId}` 호출 없이 구매 폼 하나만 제공
- 왜 안 썼나: 사용자가 상품 조회를 명시적으로 요청함. 또한 기존 조회 화면 패턴을 재사용할 좋은 기회이기도 함.

### 대안 C: CORS를 serverA에 Spring `WebMvcConfigurer`로 직접 설정
- 무엇: serverA 백엔드 코드에 CORS 허용 Bean 추가
- 왜 안 썼나: 이번 작업 범위를 프론트로 한정하고 싶었고, Next.js `rewrites`만으로 충분히 해결됨 — 백엔드 코드를 안 건드리는 쪽이 더 단순함.

### 대안 D: 구매 폼에 React Hook Form + Zod 도입
- 무엇: 7개 필드 폼이라 RHF+Zod로 구조화
- 왜 안 썼나: 각 필드가 단순 필수값 검증(서버의 `@NotBlank`/`@Min`과 동일 수준)이라 `useState` 단일 객체로도 충분 — `frontend` skill의 "복잡한 폼에만 RHF+Zod" 원칙 유지.

## 기존 코드베이스 컨벤션
- `frontend/src/app/orders/[orderId]/page.tsx` — Server Component + Suspense + try/catch 에러 처리 패턴의 기준 예시 (이번 `/purchase/[goodsId]`도 동일 패턴)
- `frontend/src/app/page.tsx` — "검색 폼 Client Component → `router.push`로 동적 라우트 이동" 패턴의 기준 예시
- `frontend/src/lib/api/client.ts` — serverB용 openapi-fetch 래퍼, `NEXT_PUBLIC_API_MODE`/`NEXT_PUBLIC_API_BASE_URL` 환경변수 패턴. `client-a.ts`도 동일한 환경변수 네이밍 관례를 따름(`NEXT_PUBLIC_SERVER_A_BASE_URL`)
- serverA `PromotionController`(`/api/v1/promotions/purchase`), `GoodsController`(`/api/v1/goods/{goodsId}`) — 이번에 연동할 실제 엔드포인트
- serverA 포트 8080 (application.yaml 확인), CORS 설정 없음

## 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/controller/PromotionController.java`, `GoodsController.java` — 연동 대상 실제 구현
- `serverA/src/main/java/promotion/serverA/dto/request/PurchaseRequest.java`, `dto/response/PurchaseResponse.java`, `dto/response/GoodsResponse.java` — 계약 작성 시 참조
- `frontend/src/app/orders/[orderId]/page.tsx` — 재사용할 Server Component 패턴
- `gateway-service/.../filter/JwtAuthFilter.java` — 참고(이번엔 게이트웨이 경유 안 하고 serverA 직접 호출, 인증 비범위)

## 외부 참조
없음
