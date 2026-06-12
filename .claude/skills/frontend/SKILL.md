---
name: frontend
description: React+TS+Vite 프론트엔드 화면/컴포넌트 작성, shadcn/ui 적용, API 연동(orval/TanStack Query) 작업 시에만 활성화. 사용자가 화면, 컴포넌트, 페이지, shadcn, 프론트, React, 라우팅, API 연동 을 언급하거나 frontend/src/ 폴더 작업 시 발동. 백엔드 코드나 인프라 작업에서는 발동하지 않음.
---

# React + shadcn/ui + OpenAPI 계약 자동화 작업 패턴

## 0. 작업 시작 전

새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 유사한 기존 페이지 1개 (`frontend/src/pages/`)
- `frontend/src/api/generated/` 의 관련 타입·훅
- `frontend/components.json` (shadcn 설정)

기존 명명 규칙, 폴더 구조, 컴포넌트 조합 방식을 **그대로 따른다**. 새 패턴을 도입하지 마라.

## 1. 디렉토리 구조

```
frontend/src/
├── api/
│   ├── axios-instance.ts      # orval mutator — 직접 수정
│   └── generated/             # orval 자동 생성 — 절대 직접 수정 금지
├── components/ui/             # shadcn CLI로 추가된 컴포넌트
├── pages/                     # 라우트 단위 화면
├── lib/
└── mocks/                     # MSW 핸들러 (테스트용)
```

## 2. API 연동 — OpenAPI 스펙 → 타입/훅 자동 생성

백엔드(serverB 등)가 springdoc-openapi로 노출하는 `/v3/api-docs`를 orval이 읽어
TypeScript 타입과 TanStack Query 훅을 생성한다.

```bash
npm run generate:api   # orval.config.ts 기준으로 frontend/src/api/generated/ 갱신
```

**규칙:**
- `frontend/src/api/generated/` 안의 파일은 생성물이다. 직접 수정하지 말고 백엔드 스펙을 바꾸거나 `orval.config.ts`를 조정한 뒤 재생성한다.
- 백엔드 컨트롤러/DTO를 변경했다면 화면 작업 전에 반드시 `npm run generate:api`를 다시 실행해 타입을 동기화한다.
- 생성된 훅은 `useQuery` 기반이며 `QueryClientProvider`(이미 `main.tsx`에 등록됨) 하위에서만 동작한다.

```tsx
import { useGetOrderStatus } from '@/api/generated/order-query-controller/order-query-controller'

const { data, isFetching, isError } = useGetOrderStatus(orderId, {
  query: { enabled: orderId !== '' },
})
```

## 3. 화면 작성 — shadcn/ui

디자이너 없이 shadcn/ui 컴포넌트 조합만으로 화면을 구성한다. 새 컴포넌트가 필요하면
직접 작성하지 말고 CLI로 추가한다.

```bash
npx shadcn@latest add <component-name>
```

```tsx
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
```

**규칙:**
- `components/ui/` 안의 생성된 컴포넌트는 가능한 한 그대로 쓴다. 스타일 커스터마이징은 `className` 합성(`cn()`)으로 한다.
- 페이지 최상단에는 시맨틱 헤딩(`<h1>`)이 있어야 한다. shadcn `CardTitle`은 `<div>`로 렌더링되므로
  시각적으로 제목을 감추고 싶다면 `<h1 className="sr-only">...</h1>`을 별도로 둔다 (접근성 — `frontend-testing` 참조).

## 4. 폼 상태/검증, 클라이언트 전역 상태 — 컨벤션 (필요해질 때 도입)

아래 두 가지는 **실제로 필요한 화면이 생겼을 때만** 라이브러리를 설치하고 코드를 작성한다.
지금 당장 보일러플레이트를 깔아두지 않는다 (Simplicity First — 사용처 없는 추상화 금지).

### 4-1. 폼 상태 관리 및 검증

단순한 입력(현재 `OrderStatusPage`처럼 필드 1~2개)은 `useState`로 충분하다.
**여러 필드, 단계별 검증, 서버 에러 매핑이 필요한 복잡한 폼**이 생기면 그때
**React Hook Form + Zod** 조합을 도입한다.

- Zod 스키마는 백엔드 Bean Validation 규칙(`@NotBlank`, `@Size` 등)과 짝을 맞춰 작성한다 —
  검증 규칙이 프론트/백엔드에서 따로 놀지 않도록 하는 것이 핵심.
- React Hook Form은 비제어 컴포넌트 기반이라 필드가 많은 폼에서 리렌더링 비용이 적다.

### 4-2. 순수 클라이언트 전역 상태

서버에서 가져온 데이터는 TanStack Query가 담당한다 (`api/generated/`의 훅).
**모달 열림 여부, 다크모드, 사이드바 토글처럼 서버와 무관한 UI 상태**가 여러 컴포넌트에
걸쳐 필요해지면 **Zustand**를 도입한다.

- Context API보다 가볍고, 구독한 값이 바뀔 때만 리렌더링되어 불필요한 리렌더링이 적다.
- "서버 상태 = TanStack Query, 클라이언트 UI 상태 = Zustand"로 역할을 분리해 둔다 — 같은 데이터를
  두 곳에 중복으로 들고 있지 않도록 주의한다.

## 5. 라우팅

React Router로 페이지를 등록한다. `BrowserRouter`는 `main.tsx`에 이미 등록되어 있으므로
`App.tsx`의 `<Routes>`에 라우트만 추가한다.

```tsx
<Routes>
  <Route path="/" element={<OrderStatusPage />} />
</Routes>
```

## 6. 로컬 개발 — CORS

프론트(`localhost:5173`)와 백엔드(예: serverB `localhost:8081`)는 origin이 다르다.
백엔드가 CORS 헤더를 보내지 않으므로, 로컬 개발에서는 **Vite dev proxy**로 우회한다
(`vite.config.ts`의 `server.proxy`, `axios-instance.ts`의 `baseURL: '/'`).

```ts
// vite.config.ts
server: { proxy: { '/api': 'http://localhost:8081' } }
```

⚠️ 이 프록시는 `npm run dev` 환경 전용이다. 운영 배포 시에는 백엔드에
`WebMvcConfigurer` 기반 CORS 설정이 별도로 필요하다 (백엔드 작업 — `api` skill 영역).

## 7. 자가 점검
- [ ] 유사한 기존 페이지/컴포넌트를 먼저 읽었는가?
- [ ] `api/generated/`를 직접 수정하지 않았는가? (스펙 변경 → `npm run generate:api` 재생성)
- [ ] shadcn CLI로 컴포넌트를 추가했는가? (직접 작성 지양)
- [ ] 폼이 복잡해졌다면 React Hook Form+Zod, 클라이언트 UI 상태가 여러 컴포넌트에 걸친다면
      Zustand 도입을 검토했는가? (단순한 경우엔 `useState`로 충분 — 4번 참조)
- [ ] 페이지에 시맨틱 `<h1>`이 있는가?
- [ ] `npm run build` (타입체크 포함) 통과하는가?
- [ ] 브라우저에서 실제 동작을 확인했는가? (CLAUDE.md UI 검증 원칙)
