---
name: frontend-testing
description: 프론트엔드 테스트 코드 작성 시에만 활성화. Vitest, React Testing Library(RTL), MSW, Playwright, axe-core 사용. 사용자가 프론트 테스트, 컴포넌트 테스트, E2E, MSW, Playwright, 시각 회귀, 접근성 을 언급하거나 frontend/src/**/*.test.tsx, frontend/e2e/ 폴더 작업 시 발동. 프론트 프로덕션 코드 작성 시에는 발동하지 않음.
---

# 프론트엔드 테스트 피라미드

백엔드(`testing` skill의 슬라이스 테스트 피라미드)와 동일한 사고방식을
프론트엔드에 적용한다: 빠르고 좁은 테스트를 많이, 느리고 넓은 테스트를 적게.

Next.js App Router 전환으로 생긴 중요한 전제: **MSW는 브라우저(Client Component) fetch만
가로챈다. Server Component의 fetch(Node에서 실행)는 MSW로 가짜 응답을 못 받는다.**
그래서 두 레이어의 책임이 나뉜다.

| 레이어 | 도구 | 범위 | 위치 |
|---|---|---|---|
| Client Component 단위 | Vitest + RTL | 단일 컴포넌트 렌더링·인터랙션 | `src/**/*.test.tsx` |
| API 통합 (Client Component만) | Vitest + MSW | Client Component + 네트워크 모킹 | `src/**/*.test.tsx` |
| Server Component 렌더링 결과 | Playwright E2E | 실제 백엔드(또는 Prism)로 끝까지 동작 확인 | `e2e/**/*.spec.ts` |

## 1. Client Component 단위 테스트 (Vitest + RTL)

```tsx
it('주문 ID 입력란과 조회 버튼을 렌더링한다', async () => {
  render(<SearchForm />)
  expect(screen.getByPlaceholderText('주문 ID')).toBeInTheDocument()
})
```

- `getByRole`/`getByPlaceholderText`/`getByText` 등 사용자 관점 쿼리를 우선한다 (`getByTestId`는 최후 수단).
- `@testing-library/user-event`로 실제 사용자 입력을 흉내낸다 (`fireEvent`보다 우선).
- 네비게이션(`router.push`)만 하는 Client Component는 `next/navigation`의 `useRouter`를 모킹해서 호출 여부만 검증한다 — 실제 라우팅은 Playwright E2E가 담당.

## 2. API 통합 테스트 (MSW) — Client Component의 조작(mutation)만

MSW는 **Client Component가 직접 호출하는** 요청만 모킹 대상이다 (예: 삭제 버튼 클릭).
Server Component가 서버에서 가져오는 조회 데이터는 MSW 범위 밖이다 (3번 참조).

```ts
// src/mocks/handlers.ts
import type { components } from '@/api/generated/types'

export const handlers = [
  http.delete('http://localhost:8081/api/v1/orders/:orderId', ({ params }) => {
    const body = { orderId: params.orderId, status: 'DELETED' } satisfies components['schemas']['OrderStatusResponse']
    return HttpResponse.json(body)
  }),
]
```

**규칙:**
- `onUnhandledRequest: 'error'`로 설정되어 있으므로, 핸들러 없는 요청은 테스트가 실패한다 — 누락 방지 장치.
- 핸들러 응답에 **`satisfies components['schemas'][...]`** 타입 단언을 반드시 붙인다 — `contracts/openapi.yaml`에서 생성된 타입과 벗어나면 컴파일 타임에 바로 에러가 나서, 별도 도구 없이 계약 드리프트를 잡아준다.
- **핸들러 경로는 상대경로(`/api/v1/...`)가 아니라 `client.ts`의 `baseUrl`과 똑같은 절대 URL로 써야 한다.** jsdom의 기본 origin은 `client.ts`가 실제로 호출하는 origin(`NEXT_PUBLIC_API_BASE_URL` 또는 기본값 `http://localhost:8081`)과 다르기 때문에, 상대경로 핸들러는 매칭되지 않고 `onUnhandledRequest: 'error'`로 테스트가 실패한다. `client.ts`의 baseUrl이 바뀌면 핸들러의 URL도 같이 바꿔야 한다. 여러 백엔드를 쓰면(`client-a.ts` 등) 원본보다 **origin 와일드카드**(`http.post('*/api/v1/...')`)로 쓰는 게 더 안전하다 — 어느 origin으로 요청이 가든 경로만 맞으면 잡힌다.

### 2-1. MSW 서버 생명주기 — `server.listen()`은 반드시 setup 파일 최상위에서

```ts
// src/test/setup.ts
import { afterEach, afterAll } from 'vitest'
import { server } from '@/mocks/server'

// beforeAll(() => server.listen()) 으로 쓰면 안 된다 — openapi-fetch의 createClient()가
// `fetch: baseFetch = globalThis.fetch`로 fetch 참조를 **호출 시점(모듈 import 시점)에**
// 캡처한다. beforeAll 콜백은 모든 import가 끝난 뒤에야 실행되므로, 그 안에서 server.listen()을
// 불러봐야 이미 캡처된(= MSW가 패치하기 전의) fetch는 못 바꾼다. 결과적으로 요청이 MSW를
// 거치지 않고 실제 네트워크로 나가 catch 블록(네트워크 에러)으로 빠진다.
server.listen({ onUnhandledRequest: 'error' })
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
```

### 2-2. `vitest.config.ts`에서 `e2e/` 폴더를 제외할 것

Playwright `.spec.ts` 파일은 Vitest 테스트가 아닌데, 기본 설정이면 Vitest가 이것도 집어서 실행하려다 실패한다.

```ts
test: {
  exclude: ['node_modules/**', 'e2e/**'],
},
```

## 3. Server Component 렌더링 결과 — Playwright E2E로 검증

조회 페이지(Server Component)가 실제로 올바른 데이터를 렌더링하는지는 **E2E로만** 검증할 수 있다.
`e2e/` 디렉토리, `playwright.config.ts`에서 `npm run dev`를 자동 기동한다 (`webServer`).
**로컬에서 백엔드(serverB, 8081)가 떠 있어야** 의미 있는 응답을 받는다 — 안 떠 있으면
`npm run mock:api`(Prism)로 띄운 mock을 보도록 `NEXT_PUBLIC_API_MODE=mock`으로 전환해서 돌린다.

```ts
test('주문 ID로 조회하면 상태가 표시된다', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('주문 ID').fill('order-123')
  await page.getByRole('button', { name: '조회' }).click()
  await expect(page).toHaveURL('/orders/order-123')
  await expect(page.getByText(/주문 order-123의 상태/)).toBeVisible()
})
```

### 3-1. 시각 회귀 (Visual Regression)

`toHaveScreenshot()`으로 스냅샷을 비교한다. 최초 실행 시 베이스라인이 없으므로
`--update-snapshots`로 생성한 뒤 커밋한다. 의도적인 UI 변경 시에만 베이스라인을 갱신한다.

```ts
await expect(page).toHaveScreenshot('order-status.png')
```

```bash
npx playwright test --update-snapshots   # 베이스라인 생성/갱신
```

**규칙:**
- 시각 회귀는 **핵심 화면 1~2개로 제한**한다. 화면마다 전부 걸면 디자인이 바뀔 때마다 베이스라인 갱신 비용이 기하급수적으로 늘어 혼자 유지하기 부담스러워진다 (Simplicity First).
- 스냅샷 파일명에 플랫폼이 포함된다(`-chromium-win32.png` 등). **Windows 로컬에서 만든 베이스라인은 Linux CI에서 그대로 쓸 수 없다** — CI에서 돌릴 계획이면 Linux용 베이스라인을 별도로 만들어야 하고, 로컬 전용(지금 상태)이면 문제없다.

### 3-2. 접근성 (axe-core)

`@axe-core/playwright`로 접근성 위반을 검사한다. **위반 0건**을 기준으로 한다.

```ts
const results = await new AxeBuilder({ page }).analyze()
expect(results.violations).toEqual([])
```

흔한 위반 예: 페이지에 `<h1>` 부재 (`page-has-heading-one`) — shadcn `CardTitle`은 `<div>`이므로
`<h1 className="sr-only">제목</h1>`을 별도로 둬야 할 수 있다 (`frontend` skill 4번 참조).

## 4. 실행 명령어 (CLAUDE.md 테스트 분업 원칙 — 비용/환경 기준)

프론트는 아직 CI에 테스트 job이 없다 — 로컬 실행이 유일한 방어선이므로 Vitest는 기본적으로 Claude가 직접 돌린다.

```bash
npm run test       # Vitest (Client Component + MSW) — 수 초, Claude가 직접 실행, 통과할 때까지 수정
npm run test:e2e   # Playwright E2E (Server Component 포함 전체 흐름)
```

- `npm run test`(Vitest): **Claude가 직접 실행**한다. 느려야 수 초이고 외부 인프라가 필요 없다.
- `npm run test:e2e`(Playwright): 브라우저 + 실제 백엔드(또는 Prism mock)가 필요하다. 로컬 dev 서버와 백엔드가 **이미 떠 있으면 Claude가 직접 실행**, 아니면 사용자에게 실행을 요청한다 — Claude가 그걸 위해 새로 백엔드/Prism을 띄우지는 않는다. 시각 회귀 스냅샷·axe 결과는 스크린샷을 사용자가 한 번 눈으로 확인하는 걸 권장한다.
- Claude가 직접 실행한 경우 실행한 명령어와 결과를 보고에 포함한다. 실행하지 못한 경우(환경 없음)엔 "이 테스트를 실행해보세요"라고 명시한다.

## 5. 자가 점검
- [ ] Client Component 단위 테스트 + MSW 통합 테스트(조작만)를 작성했는가?
- [ ] MSW 핸들러 응답에 `satisfies components['schemas'][...]` 단언을 붙였는가?
- [ ] Server Component가 렌더링하는 조회 결과는 Playwright E2E로 검증했는가? (MSW로 커버 안 됨)
- [ ] E2E에 정상 흐름 시나리오가 있는가?
- [ ] 시각 회귀 스냅샷과 접근성(axe-core, violations 0) 검사를 포함했는가?
- [ ] `npm run test`를 직접 실행해 통과를 확인했는가? `npm run test:e2e`는 환경이 되면 직접, 아니면 사용자에게 요청했는가?
