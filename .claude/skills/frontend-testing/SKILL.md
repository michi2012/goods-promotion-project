---
name: frontend-testing
description: 프론트엔드 테스트 코드 작성 시에만 활성화. Vitest, React Testing Library(RTL), MSW, Playwright, axe-core 사용. 사용자가 프론트 테스트, 컴포넌트 테스트, E2E, MSW, Playwright, 시각 회귀, 접근성 을 언급하거나 frontend/src/**/*.test.tsx, frontend/e2e/ 폴더 작업 시 발동. 프론트 프로덕션 코드 작성 시에는 발동하지 않음.
---

# 프론트엔드 테스트 피라미드

백엔드(`testing` skill의 슬라이스 테스트 피라미드)와 동일한 사고방식을
프론트엔드에 적용한다: 빠르고 좁은 테스트를 많이, 느리고 넓은 테스트를 적게.

| 레이어 | 도구 | 범위 | 위치 |
|---|---|---|---|
| 컴포넌트 단위 | Vitest + RTL | 단일 컴포넌트 렌더링·인터랙션 | `src/**/*.test.tsx` |
| API 통합 | Vitest + MSW | 컴포넌트 + 네트워크 모킹 | `src/**/*.test.tsx` (같은 파일에 포함 가능) |
| E2E | Playwright | 브라우저에서 실제 화면 흐름 | `e2e/**/*.spec.ts` |

## 1. 컴포넌트 단위 테스트 (Vitest + RTL)

`QueryClientProvider`가 필요한 컴포넌트는 테스트 전용 클라이언트로 감싼다 (재시도 비활성화).

```tsx
function renderWithQueryClient() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderStatusPage />
    </QueryClientProvider>,
  )
}

it('주문 ID 입력란과 조회 버튼을 렌더링한다', () => {
  renderWithQueryClient()
  expect(screen.getByPlaceholderText('주문 ID')).toBeInTheDocument()
})
```

- `getByRole`/`getByPlaceholderText`/`getByText` 등 사용자 관점 쿼리를 우선한다 (`getByTestId`는 최후 수단).
- `@testing-library/user-event`로 실제 사용자 입력을 흉내낸다 (`fireEvent`보다 우선).

## 2. API 통합 테스트 (MSW)

실제 네트워크 대신 `frontend/src/mocks/handlers.ts`의 핸들러로 응답을 모킹한다.
핸들러는 백엔드의 **실제 엔드포인트 경로**(`/api/v1/orders/:orderId/status`)를 그대로 사용한다 —
Vite dev proxy와 동일한 상대 경로이므로 `axios-instance`의 `baseURL`과 충돌하지 않는다.

```ts
// src/mocks/handlers.ts
export const handlers = [
  http.get('/api/v1/orders/:orderId/status', ({ params }) => {
    const { orderId } = params
    if (orderId === 'order-123') return HttpResponse.json({ orderId, status: 'SHIPPED' })
    return HttpResponse.json({ orderId, status: 'NOT_FOUND' })
  }),
]
```

`src/test/setup.ts`에서 MSW 서버 생명주기를 관리한다 (`listen` → `resetHandlers` → `close`).
새 엔드포인트를 호출하는 화면을 테스트하려면 `handlers.ts`에 핸들러를 추가한다.

**규칙:**
- `onUnhandledRequest: 'error'`로 설정되어 있으므로, 핸들러 없는 요청은 테스트가 실패한다 — 누락 방지 장치.
- 핸들러의 응답 스키마는 `api/generated/model/`의 타입과 일치해야 한다 (계약 검증 역할 겸함).

## 3. E2E 테스트 (Playwright)

`e2e/` 디렉토리, `playwright.config.ts`에서 `npm run dev`를 자동 기동한다 (`webServer`).
실제 브라우저에서 화면 흐름을 검증하므로 **백엔드가 로컬에서 떠 있어야** 의미 있는 응답을 받는다.

```ts
test('주문 ID를 입력해 조회하면 상태가 표시된다', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('주문 ID').fill('order-123')
  await page.getByRole('button', { name: '조회' }).click()
  await expect(page.getByText(/주문 order-123의 상태/)).toBeVisible()
})
```

### 3-1. 시각 회귀 (Visual Regression)

`toHaveScreenshot()`으로 스냅샷을 비교한다. 최초 실행 시 베이스라인이 없으므로
`--update-snapshots`로 생성한 뒤 커밋한다. 의도적인 UI 변경 시에만 베이스라인을 갱신한다.

```ts
await expect(page).toHaveScreenshot('order-status-initial.png')
```

```bash
npx playwright test --update-snapshots   # 베이스라인 생성/갱신
```

### 3-2. 접근성 (axe-core)

`@axe-core/playwright`로 접근성 위반을 검사한다. **위반 0건**을 기준으로 한다.

```ts
const results = await new AxeBuilder({ page }).analyze()
expect(results.violations).toEqual([])
```

흔한 위반 예: 페이지에 `<h1>` 부재 (`page-has-heading-one`) — shadcn `CardTitle`은 `<div>`이므로
`<h1 className="sr-only">제목</h1>`을 별도로 둬야 할 수 있다 (`frontend` skill 4번 참조).

## 4. 실행 명령어 (CLAUDE.md 테스트 분업 원칙 — 작성은 Claude, 실행은 사용자)

```bash
npm run test       # Vitest (컴포넌트 + MSW 통합)
npm run test:e2e   # Playwright E2E
```

테스트 코드를 작성/수정한 뒤에는 위 명령어를 실행해보라고 사용자에게 안내한다.
실패 로그를 사용자가 붙여넣으면 그때 분석·수정한다.

## 5. 자가 점검
- [ ] 컴포넌트 단위 테스트 + MSW 통합 테스트를 모두 작성했는가?
- [ ] MSW 핸들러 응답이 `api/generated/model/` 타입과 일치하는가?
- [ ] E2E에 정상 흐름 시나리오가 있는가?
- [ ] 시각 회귀 스냅샷과 접근성(axe-core, violations 0) 검사를 포함했는가?
- [ ] `npm run test`, `npm run test:e2e` 실행을 사용자에게 안내했는가?
