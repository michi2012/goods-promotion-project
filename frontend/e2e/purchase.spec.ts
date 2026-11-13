import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

// serverA(8080)가 로컬에서 떠 있어야 의미 있게 통과한다. 테스트가 직접 상품을 생성해서
// 매번 독립적으로 검증할 수 있게 한다(수동으로 미리 만들어둔 데이터에 의존하지 않음).
let goodsId: number

test.beforeAll(async ({ request }) => {
  const res = await request.post('http://localhost:8080/api/v1/goods', {
    data: { name: 'E2E 테스트 상품', price: 15000, stock: 5 },
  })
  const body = await res.json()
  goodsId = body.id
})

// 모의 PG는 1% 확률로 결제를 거절하므로(정상 동작) PAID 확인 테스트는 한 번만 재시도한다.
test.describe.configure({ retries: 1 })

test('상품을 조회하고 구매하면 주문 상태 페이지에 PAID가 표시된다', async ({ page }) => {
  await page.goto(`/purchase/${goodsId}`)
  await expect(page.getByText('E2E 테스트 상품')).toBeVisible()

  // 단가 표시 + 수량 변경 시 예상 결제 금액(표시용) 갱신
  await page.getByPlaceholder('수량').fill('2')
  await expect(page.getByText('30,000원')).toBeVisible()
  // 결과 보고용 근거: 확인한 바로 그 순간의 화면을 첨부한다 (테스트 종료 후 자동 스크린샷은 재렌더 시점과 겹칠 수 있음)
  await test.info().attach('근거: 예상 결제 금액 30,000원', { body: await page.screenshot(), contentType: 'image/png' })

  await page.getByPlaceholder('결제 수단').fill('CARD')
  await page.getByPlaceholder('배송지 주소').fill('서울시 강남구 테헤란로 1')
  await page.getByPlaceholder('우편번호').fill('06236')
  await page.getByPlaceholder('전화번호').fill('010-1234-5678')
  await page.getByPlaceholder('이메일').fill('e2e@example.com')
  await page.getByRole('button', { name: '구매하기' }).click()

  await expect(page).toHaveURL(/\/orders\/.+/)
  await expect(page.getByText(/의 상태:/)).toBeVisible()

  // 화면 이동이 아니라 최종 결과(PAID)까지 확인한다. 주문 상태는 Server Component라 자동 갱신되지 않으므로
  // 새로고침하며 Saga 확정(결제·상태 갱신 → PAID)을 기다린다.
  await expect
    .poll(
      async () => {
        await page.reload()
        return (await page.locator('span.font-medium').first().textContent())?.trim()
      },
      { message: '주문 상태 PAID 대기', timeout: 60_000, intervals: [2_000, 3_000, 5_000] },
    )
    .toBe('PAID')
  await expect(page.getByText('PAID', { exact: true })).toBeVisible()
  await test.info().attach('근거: 주문 상태 PAID 화면', { body: await page.screenshot(), contentType: 'image/png' })
})

test('상품 검색 화면은 접근성 위반이 없다', async ({ page }) => {
  await page.goto('/purchase')
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
})

test('구매 폼 화면은 접근성 위반이 없다', async ({ page }) => {
  await page.goto(`/purchase/${goodsId}`)
  await expect(page.getByText('E2E 테스트 상품')).toBeVisible()
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
})
