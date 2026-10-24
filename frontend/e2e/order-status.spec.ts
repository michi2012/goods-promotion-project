import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

// webServer는 Next.js dev 서버만 띄운다. 이 테스트는 실제 serverB(8081)+redis-b(6380)가
// 로컬에서 떠 있어야 의미 있는 응답을 받는다 (안 떠 있으면 NEXT_PUBLIC_API_MODE=mock + `npm run mock:api`로 전환).
// 임의의 주문 ID는 Redis에 값이 없으므로 NOT_FOUND가 반환되는 것이 정상이며, 별도 데이터 시딩 없이 결정론적으로 검증 가능하다.

test('주문 ID를 입력해 조회하면 결과 페이지로 이동하고 상태가 표시된다', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('주문 ID').fill('e2e-test-order')
  await page.getByRole('button', { name: '조회' }).click()

  await expect(page).toHaveURL('/orders/e2e-test-order')
  await expect(page.getByText('NOT_FOUND')).toBeVisible()
})

test('홈 화면은 접근성 위반이 없다', async ({ page }) => {
  await page.goto('/')
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
})

test('조회 결과 화면은 접근성 위반이 없다', async ({ page }) => {
  await page.goto('/orders/e2e-test-order')
  await expect(page.getByText('NOT_FOUND')).toBeVisible()
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
})

test('조회 결과 화면 시각 회귀', async ({ page }) => {
  await page.goto('/orders/e2e-test-order')
  await expect(page.getByText('NOT_FOUND')).toBeVisible()
  await expect(page).toHaveScreenshot('order-status.png')
})
