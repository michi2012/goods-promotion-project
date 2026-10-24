# 맥락 노트: 프론트엔드 전면 재작성 — Next.js + Contract-first API

## 왜 이 방식을 선택했는가
사용자가 프론트엔드 규칙을 처음부터 뒤집기로 하면서 다음 세 가지를 요청함:
1. React+Next.js+TS+Tailwind+shadcn/ui
2. API 명세/연동을 "백/프론트가 이미 분리되어 있다"는 가정하에, OpenAPI 계약을 먼저 작성하고(code-first가 아닌 contract-first) `openapi-typescript`+`Prism`으로 연동
3. Vitest+RTL+MSW+Playwright 테스트 피라미드 (기존과 동일 — 변경 없음)

대화 중 아래 세부 결정을 거쳤다:
- **계약 운영**: `contracts/openapi.yaml`을 사람이 먼저 쓰는 유일한 원본으로 두되, CI에 drift-check(백엔드 springdoc 생성 스펙과 정규화 diff)를 둬서 계약-구현 불일치를 잡는다. 처음엔 "스펙 파일 있으면 그걸 쓰고 없으면 코드에서 생성"하는 조건부 fallback을 고려했으나, 이러면 소스가 시점마다 바뀌어 혼란스럽고 애초에 contract-first를 쓰는 이유(백엔드 구현 순서와 프론트 분리)가 무력화돼서 폐기했다.
- **데이터 페칭 전략**: 처음엔 "전부 Client Component+TanStack Query"를 제안했으나, 이렇게 하면 Next.js의 핵심 기능(RSC)을 안 쓰는 셈이라 "굳이 Next.js를 쓰는 이유가 뭐냐"는 반론을 받고 재검토. 사용자가 Next.js를 고른 이유가 특정 SEO 요구가 아니라 "최근 추세를 따르고 싶어서"라고 확인했으므로, 추세 자체가 RSC 활용이라는 점에서 하이브리드(조회=Server Component 직접 fetch, 조작=Client Component)로 최종 확정. TanStack Query는 폼/Zustand와 동일하게 "실제로 필요해질 때만" 도입하는 걸로 축소 — 처음엔 조작에도 TanStack Query를 끼워 넣으려 했으나, 이 화면엔 조작 자체가 없어서(순수 조회) 당장은 아예 안 씀.
- **CI drift-check**: 한 번 "학습용이라 생략하자"로 뺐다가 사용자가 다시 "그냥 추가하자"로 뒤집어 최종적으로 포함됨.
- **범위**: 처음엔 "scaffold+컨벤션만" vs "기존 주문조회 화면까지 재구현"을 두고 고민했는데, 컨벤션이 실제로 동작하는지 끝까지 보고 싶다는 이유로 후자(B)를 선택.
- **단계 순서**: 최초 계획은 skill 문서(`frontend`/`frontend-testing`) 재작성을 마지막 단계(7)에 뒀었는데, 사용자가 "프론트 규칙을 미리 수정하기로 한 것 아니냐"고 지적해 바로잡음. 이 대화 전체의 목적 자체가 "새 프론트 규칙 결정"이고 실제 구현은 그 규칙을 검증하는 과정이므로, skill 문서 재작성을 1단계로 옮기고 이후 구현 단계가 그 문서를 참조하도록 재정렬함 — 기존 백엔드 skill(`api`/`jpa`/`exception`)이 "규칙 문서 먼저, 구현은 참조"하는 패턴과 일치시킴.

## 검토했으나 채택하지 않은 대안
### 대안 A: Vite+React 유지
- 무엇: Next.js 대신 기존 Vite 스택 유지
- 왜 안 썼나: 사용자가 Next.js로 전환을 명시적으로 요청함. (다만 "전부 Client 렌더링"으로 갔다면 Vite가 더 단순했을 거라는 지적이 있었고, 이 지적 때문에 하이브리드 RSC 전략으로 조정됨)

### 대안 B: Code-first (백엔드 springdoc 스펙을 원본으로)
- 무엇: Spring 컨트롤러/DTO를 먼저 구현하고 거기서 openapi.yaml을 생성, 그걸 프론트가 그대로 소비
- 왜 안 썼나: 혼자 개발이라 "계약과 구현을 둘 다 손으로 맞추는 중복"이 번거롭다는 지적이 있었지만, 이렇게 가면 프론트가 다시 백엔드 구현 완료를 기다려야 해서 애초에 contract-first로 바꾼 이유(분리된 개발)가 사라짐. 사용자가 "백/프론트 분리돼 있다고 가정"을 재확인하면서 contract-first를 유지.

### 대안 C: 계약 파일 존재 여부에 따른 조건부 fallback (사람이 쓴 게 있으면 그거, 없으면 코드에서 생성)
- 무엇: `contracts/openapi.yaml`이 있으면 그걸 쓰고, 없으면 백엔드 생성 스펙을 쓰는 조건문
- 왜 안 썼나: 소스가 시점마다 바뀌어 "지금 보고 있는 타입이 계약인지 구현 결과물인지" 혼란스러워지고, 드리프트를 조용히 숨기는 효과가 있어서 폐기. 대신 "유일한 소스 + CI 상에서 사람이 판단하는 드리프트 알림"으로 정리.

### 대안 D: 전부 Client Component + TanStack Query로 통일
- 무엇: Server Component를 안 쓰고 모든 데이터 페칭을 클라이언트에서
- 왜 안 썼나: Next.js를 쓰는 의미가 없어진다는 반론(Vite가 더 단순한 대안이 됨)을 받아들여 폐기. 최종적으로 "조회=Server Component, 조작=Client Component(+필요시에만 TanStack Query)"로 조정.

## 기존 코드베이스 컨벤션 (frontend/ 재작성 전 기준)
- 기존 `frontend/src/pages/OrderStatusPage.tsx`: `useState`로 입력값 관리, 조회 버튼 클릭 시 `useGetOrderStatus`(orval 생성 훅) 호출, `isFetching`/`isError`/`data` 분기 렌더. 새 구현에서도 이 상태 전이(조회중/에러/성공) UX는 그대로 유지한다.
- 기존 `frontend/src/api/generated/`: orval이 serverB의 `/v3/api-docs`를 읽어 생성 — 이번 작업으로 폐기, `openapi-typescript` 생성물로 대체.
- 백엔드 `serverB/src/main/java/promotion/serverB/controller/OrderQueryController.java`: `GET /api/v1/orders/{orderId}/status`(주문 상태), `GET /api/v1/goods/{goodsId}/stock`(재고 — 프론트에서 미사용, 이번 계약에서 제외).
- `serverB`는 로컬에서 포트 8081로 기동 (`application.yaml` 기준), Redis(6380)/Kafka(9092) 등 인프라 의존.
- CI: `.github/workflows/ci-cd.yaml` 단일 워크플로.

## 관련 파일/위치
- `frontend/src/pages/OrderStatusPage.tsx` — 재구현 대상 화면의 원본 (삭제 예정)
- `serverB/src/main/java/promotion/serverB/controller/OrderQueryController.java` — 계약 작성 시 참조할 실제 구현
- `docs/frontend-flow.md` — 기존 프론트 코드 흐름 문서. README 폴링 정책 불일치가 여기 "발견 사항"에 기록되어 있음(이번 작업 비범위).
- `.claude/skills/frontend/SKILL.md`, `.claude/skills/frontend-testing/SKILL.md` — 이번 작업 완료 후 재작성 대상
- `.claude/skills/api/SKILL.md` — 계약 파일 참조 한 줄 추가 대상

## 외부 참조
없음
