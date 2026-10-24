---
name: frontend
description: Next.js(App Router)+TS+Tailwind 프론트엔드 화면/컴포넌트 작성, shadcn/ui 적용, contract-first API 연동 작업 시에만 활성화. 사용자가 화면, 컴포넌트, 페이지, shadcn, 프론트, React, Next.js, API 연동 을 언급하거나 frontend/src/ 폴더 작업 시 발동. 백엔드 코드나 인프라 작업에서는 발동하지 않음.
---

# Next.js + shadcn/ui + Contract-first API 작업 패턴

## 0. 작업 시작 전

새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 유사한 기존 페이지 1개 (`frontend/src/app/`)
- `contracts/openapi.yaml` (API 계약 원본)
- `frontend/components.json` (shadcn 설정)

기존 명명 규칙, 폴더 구조, 컴포넌트 조합 방식을 **그대로 따른다**. 새 패턴을 도입하지 마라.

## 1. 디렉토리 구조

```
frontend/src/
├── app/                        # 라우트 (App Router)
│   ├── layout.tsx
│   ├── page.tsx
│   └── orders/[orderId]/page.tsx
├── api/
│   └── generated/
│       └── types.ts            # openapi-typescript 자동 생성 — 절대 직접 수정 금지
├── lib/
│   ├── api/
│   │   └── client.ts           # openapi-fetch 래퍼 — 직접 수정
│   └── utils.ts
├── components/ui/              # shadcn CLI로 추가된 컴포넌트
└── mocks/                      # MSW 핸들러 (테스트용)

contracts/
└── openapi.yaml                # API 계약 원본 (레포 루트, 백엔드와 공유)
```

## 2. API 연동 — Contract-first

**원본은 하나: `contracts/openapi.yaml`.** 백엔드가 떠 있는지와 무관하게 이 파일이 기준이다.

```bash
npm run generate:api   # openapi-typescript로 contracts/openapi.yaml → src/api/generated/types.ts
```

**규칙:**
- `contracts/openapi.yaml`은 **사람이 먼저 작성**한다. API 모양을 바꿀 땐 이 파일을 먼저 고치고, 백엔드 구현을 그에 맞춘다 (백엔드 쪽 규칙은 `api` skill 참조).
- `openapi-typescript`는 **타입만** 생성한다. orval처럼 TanStack Query 훅을 자동으로 만들어주지 않는다 — fetch 호출은 아래 `client.ts`를 통해 직접 작성한다.
- `src/api/generated/`는 생성물이다. 직접 수정하지 말고 `contracts/openapi.yaml`을 고친 뒤 재생성한다.
- 백엔드가 아직 구현하지 않은 엔드포인트를 먼저 개발해야 하면 **Prism**으로 임시 mock 서버를 띄운다 (예외적 상황에서만 사용 — 상시 기본값 아님):
  ```bash
  npm run mock:api   # Prism mock contracts/openapi.yaml -p 4010
  ```
  `.env.local`에 `NEXT_PUBLIC_API_MODE=mock`을 설정했을 때만 `client.ts`가 Prism을 바라본다. 기본값은 실제 로컬 백엔드(`localhost:8081`)다.

```ts
// src/lib/api/client.ts
import createClient from 'openapi-fetch'
import type { paths } from '@/api/generated/types'

const baseUrl =
  process.env.NEXT_PUBLIC_API_MODE === 'mock'
    ? 'http://localhost:4010'
    : 'http://localhost:8081'

export const client = createClient<paths>({ baseUrl })
```

이 `client`는 Server Component(Node)와 Client Component(브라우저) 양쪽에서 동일하게 import해서 쓴다.

## 3. 데이터 페칭 — 조회는 Server Component, 조작은 Client Component

**이 프로젝트는 `cacheComponents: true`(Next.js 16 기본값, Cache Components/PPR)로 scaffold됐다.** 이 설정하에선 캐시하지 않을 동적 데이터(요청마다 바뀌는 값)를 `<Suspense>` 없이 그냥 `await`하면 prerender 검증 오류가 난다. 주문 상태처럼 **매번 최신값을 봐야 하는 데이터는 캐시하면 안 되므로, `use cache`가 아니라 항상 `<Suspense>`로 감싼다** — 페이지 본체는 동기 함수로 두고, 실제 fetch는 내부의 별도 async 컴포넌트에서 한다. `params`도 Next.js 15+부터 `Promise`이므로 `await`해서 꺼낸다.

```tsx
// src/app/orders/[orderId]/page.tsx
import { Suspense } from 'react'
import { client } from '@/lib/api/client'

export default function OrderStatusPage({
  params,
}: {
  params: Promise<{ orderId: string }>
}) {
  return (
    <Suspense fallback={<p className="text-sm text-muted-foreground">조회 중...</p>}>
      <OrderStatus params={params} />
    </Suspense>
  )
}

async function OrderStatus({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params
  const { data, error } = await client.GET('/api/v1/orders/{orderId}/status', {
    params: { path: { orderId } },
  })

  if (error) return <p className="text-sm text-destructive">조회 중 오류가 발생했습니다.</p>
  return (
    <p className="text-sm">
      주문 {data.orderId}의 상태: <span className="font-medium">{data.status}</span>
    </p>
  )
}
```

**생성/삭제 같은 조작은 Client Component에서 호출하고 `router.refresh()`로 서버 데이터를 다시 가져온다.** TanStack Query는 지금 도입하지 않는다 — 폴링, optimistic update처럼 클라이언트 캐시가 실제로 값어치 있는 화면이 생기면 그때 그 화면에만 추가한다 (Simplicity First — 4번 폼/상태 원칙과 동일).

```tsx
'use client'
import { useRouter } from 'next/navigation'
import { client } from '@/lib/api/client'

export function DeleteButton({ orderId }: { orderId: string }) {
  const router = useRouter()
  async function handleDelete() {
    await client.DELETE('/api/v1/orders/{orderId}', { params: { path: { orderId } } })
    router.refresh()
  }
  return <Button onClick={handleDelete}>삭제</Button>
}
```

**CORS 참고:** Server Component의 fetch는 Node→Spring 서버 간 호출이라 브라우저 CORS가 적용되지 않는다. CORS/프록시 설정이 필요해지는 시점은 **Client Component가 브라우저에서 Spring을 직접 호출하는 조작(생성/삭제 등)이 생겼을 때뿐**이다 — 그때 `next.config.ts`의 `rewrites()` 또는 Spring CORS 설정 중 하나를 추가한다. 순수 조회만 있는 화면에는 CORS 설정이 필요 없다.

## 4. 화면 작성 — shadcn/ui

디자이너 없이 shadcn/ui 컴포넌트 조합만으로 화면을 구성한다. 새 컴포넌트가 필요하면
직접 작성하지 말고 CLI로 추가한다.

```bash
npx shadcn@latest add <component-name>
```

**규칙:**
- `components/ui/` 안의 생성된 컴포넌트는 가능한 한 그대로 쓴다. 스타일 커스터마이징은 `className` 합성(`cn()`)으로 한다.
- 페이지 최상단에는 시맨틱 헤딩(`<h1>`)이 있어야 한다. shadcn `CardTitle`은 `<div>`로 렌더링되므로
  시각적으로 제목을 감추고 싶다면 `<h1 className="sr-only">...</h1>`을 별도로 둔다 (접근성 — `frontend-testing` 참조).

## 5. 폼 상태/검증, 클라이언트 전역 상태 — 컨벤션 (필요해질 때 도입)

아래 두 가지는 **실제로 필요한 화면이 생겼을 때만** 라이브러리를 설치하고 코드를 작성한다.
지금 당장 보일러플레이트를 깔아두지 않는다 (Simplicity First — 사용처 없는 추상화 금지).

### 5-1. 폼 상태 관리 및 검증

단순한 입력(필드 1~2개)은 `useState`로 충분하다. **여러 필드, 단계별 검증, 서버 에러 매핑이
필요한 복잡한 폼**이 생기면 그때 **React Hook Form + Zod** 조합을 도입한다.

- Zod 스키마는 `contracts/openapi.yaml`의 요청 스키마와 짝을 맞춰 작성한다 — 검증 규칙이
  계약과 따로 놀지 않도록 하는 것이 핵심.

### 5-2. 순수 클라이언트 전역 상태

**모달 열림 여부, 다크모드, 사이드바 토글처럼 서버와 무관한 UI 상태**가 여러 컴포넌트에
걸쳐 필요해지면 **Zustand**를 도입한다. Context API보다 가볍다.

## 6. 자가 점검
- [ ] 유사한 기존 페이지/컴포넌트와 `contracts/openapi.yaml`을 먼저 읽었는가?
- [ ] `src/api/generated/`를 직접 수정하지 않았는가? (계약 변경 → `npm run generate:api` 재생성)
- [ ] 조회는 Server Component, 조작은 Client Component로 나눴는가? (TanStack Query는 실제로 필요해질 때만)
- [ ] 캐시하면 안 되는 동적 데이터(요청마다 바뀌는 값)를 `<Suspense>`로 감쌌는가? (Cache Components 기본값 — 3번 참조)
- [ ] 브라우저가 Spring을 직접 호출하는 조작이 있다면 CORS 설정을 했는가?
- [ ] shadcn CLI로 컴포넌트를 추가했는가? (직접 작성 지양)
- [ ] 폼이 복잡해졌다면 React Hook Form+Zod, 클라이언트 UI 상태가 여러 컴포넌트에 걸친다면
      Zustand 도입을 검토했는가? (단순한 경우엔 `useState`로 충분 — 5번 참조)
- [ ] 페이지에 시맨틱 `<h1>`이 있는가?
- [ ] `npm run build` (타입체크 포함) 통과하는가?
- [ ] 브라우저에서 실제 동작을 확인했는가? (CLAUDE.md UI 검증 원칙)
