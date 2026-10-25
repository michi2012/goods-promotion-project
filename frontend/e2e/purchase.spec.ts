import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

// serverA(8080)가 로컬에서 떠 있어야 의미 있게 통과한다. 테스트가 직접 상품을 생성해서
// 매번 독립적으로 검증할 수 있게 한다(수동으로 미리 만들어둔 데이터에 의존하지 않음).
let goodsId: number

test.beforeAll(async ({ request }) => {
  const res = await request.post('http://localhost:8080/api/v1/goods', {
    data: { name: 'E2E 테스트 상품', stock: 5 },
  })
  const body = await res.json()
  goodsId = body.id
})

test('상품을 조회하고 구매하면 주문 상태 페이지로 이동한다', async ({ page }) => {
  await page.goto(`/purchase/${goodsId}`)
  await expect(page.getByText('E2E 테스트 상품')).toBeVisible()

  await page.getByPlaceholder('결제 수단').fill('CARD')
  await page.getByPlaceholder('배송지 주소').fill('서울시 강남구 테헤란로 1')
  await page.getByPlaceholder('우편번호').fill('06236')
  await page.getByPlaceholder('전화번호').fill('010-1234-5678')
  await page.getByPlaceholder('이메일').fill('e2e@example.com')
  await page.getByRole('button', { name: '구매하기' }).click()

  await expect(page).toHaveURL(/\/orders\/.+/)
  await expect(page.getByText(/의 상태:/)).toBeVisible()
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
