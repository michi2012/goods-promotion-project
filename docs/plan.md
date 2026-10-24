# 계획서: 프론트엔드 전면 재작성 — Next.js + Contract-first API

- 작성일: 2026-10-24
- 관련 이슈/티켓: 없음
- 브랜치: refactor/frontend-nextjs-contract-first

## 목표
기존 Vite+React+orval 기반 `frontend/`를 전부 삭제하고, Next.js(App Router)+TS+Tailwind+shadcn/ui와 contract-first(OpenAPI 계약 우선) API 연동 방식으로 재작성한다. 기존에 있던 "주문 상태 조회" 화면을 새 스택/컨벤션으로 끝까지 재구현해 컨벤션이 실제로 동작함을 검증한다.

## 성공 기준
- [ ] `frontend/npm run build` 통과 (Next.js 타입체크 포함)
- [ ] `frontend/npm run test` (Vitest+RTL+MSW) 통과 — 검색 폼 컴포넌트 테스트 포함
- [ ] `frontend/npm run test:e2e` (Playwright) 통과 — 주문 조회 E2E + 접근성(axe-core, violations 0) + 시각회귀 스냅샷 포함
- [ ] 로컬에서 serverB(8081)를 띄운 상태로 실제 주문 ID를 조회해 상태가 정확히 표시되는지 브라우저로 수동 확인
- [ ] `.github/workflows/ci-cd.yaml`에 추가한 drift-check 스텝이 로컬에서 수동 실행 시(정상 상태 기준) 통과

## 비범위 (Out of Scope)
- `GET /api/v1/goods/{goodsId}/stock` 엔드포인트의 계약/화면 (현재 어떤 화면에서도 쓰이지 않음)
- README.md가 서술하는 폴링 정책(초기 대기 2s, 지수 백오프 2→4→8→16→32s, 60s 타임아웃 등) 구현 — 현재 코드와 README가 이미 불일치 상태이며, 이번 작업에서 그 불일치를 해소하지 않음 (별도 작업으로 분리)
- TanStack Query 도입 — 이번 화면(단순 1회 조회)엔 불필요. 폴링/optimistic update 등 실제로 필요한 화면이 생기면 그때 추가
- React Hook Form+Zod, Zustand 도입 — 이번 화면(입력 필드 1개)엔 불필요
- serverA/serverC/aiops/cs-bot 등 다른 백엔드 모듈에 대한 계약 작성이나 CI drift-check
- 새 디자인 토큰/다크모드 등 디자인 시스템 작업

## 단계별 작업 계획

### 단계 1: skill 문서 재작성 (규칙을 먼저 고정)
- 변경 파일: `.claude/skills/frontend/SKILL.md`(전면 재작성), `.claude/skills/frontend-testing/SKILL.md`(전면 재작성), `.claude/skills/api/SKILL.md`(계약 파일 참조 한 줄 추가)
- 변경 내용 요약: 이번 대화에서 결정한 컨벤션(Next.js App Router 구조, contract-first API 연동, 하이브리드 데이터 페칭, Prism/CI drift-check, 테스트 피라미드)을 skill 문서로 먼저 고정한다. 이후 단계(2~7)의 실제 구현은 이 문서를 따른다 — 기존 백엔드 skill(`api`/`jpa`/`exception`)과 동일하게 "규칙 문서 먼저, 구현은 그걸 참조"하는 패턴을 유지.
- 검증 방법: 육안 확인 — 이번 대화에서 결정한 모든 컨벤션이 문서에 빠짐없이 반영됐는지 체크
- 롤백 방법: 아직 커밋 전이므로 파일을 이전 내용으로 되돌림
- 예상 소요: 보통

### 단계 2: 기존 frontend/ 삭제 + Next.js 신규 scaffold (단계 1 skill 문서 기준)
- 변경 파일: `frontend/` 전체 삭제 후 `create-next-app`으로 재생성 (App Router, TS, Tailwind 기본값)
- 변경 내용 요약: 기존 Vite 기반 프로젝트를 통째로 지우고 Next.js 프로젝트로 새로 시작. 패키지 매니저는 기존과 동일하게 npm 유지.
- 검증 방법: `npm run dev` 기동 후 기본 페이지 렌더 확인, `npm run build` 통과
- 롤백 방법: 아직 커밋 전이므로 `git checkout -- frontend/` 또는 해당 커밋 자체를 만들지 않음
- 예상 소요: 짧음

### 단계 3: contracts/openapi.yaml 작성 + 타입 생성 + fetch 래퍼 (단계 1 skill 문서 기준)
- 변경 파일: `contracts/openapi.yaml`(신규, 레포 루트), `frontend/package.json`(`openapi-typescript`, `openapi-fetch` 의존성 추가 — 새 의존성), `frontend/src/api/generated/types.ts`(생성물), `frontend/src/lib/api/client.ts`(신규, openapi-fetch 래퍼)
- 변경 내용 요약: 기존 `serverB/OrderQueryController`의 `GET /api/v1/orders/{orderId}/status` 하나만 담은 OpenAPI 3.x 스펙을 사람이 작성(제가 기존 컨트롤러/DTO를 읽고 draft를 만들어 제시 → 확인받고 확정). `npm run generate:api`로 타입만 생성(훅은 생성 안 됨). `client.ts`에서 `createClient<paths>()`로 한 번만 래핑.
- 검증 방법: `npm run generate:api` 실행 후 `types.ts` 생성 확인, `tsc --noEmit` 통과
- 롤백 방법: `contracts/openapi.yaml`, 생성물, `client.ts` 삭제
- 예상 소요: 보통

### 단계 4: shadcn/ui 초기화 + 공통 레이아웃
- 변경 파일: `frontend/components.json`, `frontend/src/components/ui/{button,card,input}.tsx`(CLI로 추가), `frontend/src/app/layout.tsx`
- 변경 내용 요약: shadcn CLI로 기존 화면에서 쓰던 컴포넌트(Button/Card/Input) 추가, 기존 헤더/푸터를 `layout.tsx`로 이식
- 검증 방법: `npm run build` 통과, 빈 레이아웃 렌더 확인
- 롤백 방법: 해당 파일 삭제
- 예상 소요: 짧음

### 단계 5: 검색 폼(`/`, Client Component) + 조회 결과(`/orders/[orderId]`, Server Component)
- 변경 파일: `frontend/src/app/page.tsx`(Client Component, 주문 ID 입력 후 `router.push('/orders/{id}')`), `frontend/src/app/orders/[orderId]/page.tsx`(Server Component, `client.GET`으로 직접 fetch 후 상태 렌더)
- 변경 내용 요약: 기존 `OrderStatusPage.tsx`의 "입력→조회" 동작을 "조회=Server Component, 조작(네비게이션)=Client Component" 패턴으로 재구현. 이 화면엔 생성/삭제 같은 실제 "조작"이 없으므로, 브라우저가 Spring을 직접 호출하는 경로가 생기지 않음(= CORS 설정 불필요 — 같은 단계에서 네트워크 탭으로 확인하고 `docs/context.md`에 기록, 향후 "조작"이 있는 화면이 생기면 그때 CORS 설정이 필요해진다는 점만 skill 문서에 남김).
- 검증 방법: 로컬 serverB(8081) 기동 후 브라우저에서 실제 주문 ID로 조회해 상태 표시 확인 + 네트워크 탭에서 브라우저가 `localhost:8081`을 직접 호출하지 않음을 확인
- 롤백 방법: 두 파일 삭제
- 예상 소요: 보통

### 단계 6: 테스트 작성 (Vitest+RTL+MSW / Playwright E2E+axe-core+시각회귀)
- 변경 파일: `frontend/src/app/page.test.tsx`(검색 폼 컴포넌트 테스트), `frontend/src/mocks/handlers.ts`(MSW 핸들러, `satisfies` 타입 단언으로 drift 체크), `frontend/e2e/order-status.spec.ts`(E2E + axe-core + `toHaveScreenshot`)
- 변경 내용 요약: 기존 `frontend-testing` skill의 패턴을 그대로 적용해 테스트 작성. 실행은 사용자가 직접 (CLAUDE.md 테스트 분업 원칙).
- 검증 방법: 작성 후 사용자에게 `npm run test`, `npm run test:e2e` 실행 안내 (제가 직접 실행하지 않음)
- 롤백 방법: 해당 테스트 파일 삭제
- 예상 소요: 보통

### 단계 7: Prism mock + CI drift-check
- 변경 파일: `frontend/package.json`(`@stoplight/prism-cli` devDependency, `mock:api` 스크립트 추가), `frontend/src/lib/api/client.ts`(`NEXT_PUBLIC_API_MODE` 분기), `.github/workflows/ci-cd.yaml`(serverB 빌드 후 springdoc 생성 스펙 vs `contracts/openapi.yaml` 정규화 diff 스텝 추가)
- 변경 내용 요약: 지금까지 결정한 컨벤션을 CI로 고정. drift-check는 serverB 모듈만 대상(이번에 관련 API를 제공하는 유일한 모듈).
- 검증 방법: `npm run mock:api` 로컬 기동 확인. CI yaml 문법 검증(`actionlint` 또는 GitHub 문법 체크).
- 롤백 방법: 각 파일을 이전 상태로 되돌림
- 예상 소요: 보통

## 리스크 및 대응
- 리스크 1: Next.js 15 + React 19 + Tailwind v4 조합이 shadcn CLI 초기화와 충돌할 가능성 → 대응: 단계 4에서 조기 발견, 문제 시 즉시 보고 후 방향 재확인
- 리스크 2: `openapi-fetch`가 Node(Server Component) 환경에서 브라우저와 동일하게 동작하지 않을 가능성 → 대응: 단계 5에서 직접 확인, 문제 시 네이티브 `fetch` 직접 사용으로 대체
- 리스크 3: CI diff 정규화(yaml 키 순서/포맷)가 생각보다 까다로움 → 대응: 처음엔 `yq` 기반 단순 정렬+diff로 시작, 과도하게 정교화하지 않음(Simplicity First)

## 의존성
- 로컬 E2E 검증을 위해 `serverB`가 로컬에서 기동되어 있어야 함(CLAUDE.md 커밋 전 E2E 기준 — 로컬에서 바로 실행 가능하므로 로컬 E2E 필수)
- 신규 npm 의존성(`openapi-typescript`, `openapi-fetch`, `@stoplight/prism-cli`) 추가는 이번 계획 승인으로 사용자 동의 처리
