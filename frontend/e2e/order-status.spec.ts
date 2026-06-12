import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test.describe('주문 상태 조회 화면', () => {
  test('주문 ID를 입력해 조회하면 상태가 표시된다', async ({ page }) => {
    await page.goto('/')

    await page.getByPlaceholder('주문 ID').fill('order-123')
    await page.getByRole('button', { name: '조회' }).click()

    await expect(page.getByText(/주문 order-123의 상태/)).toBeVisible()
  })

  test('초기 화면 시각 회귀 스냅샷', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('heading', { name: '주문 상태 조회' })).toBeVisible()
    await expect(page).toHaveScreenshot('order-status-initial.png')
  })

  test('접근성 위반 사항이 없어야 한다 (axe-core)', async ({ page }) => {
    await page.goto('/')

    const results = await new AxeBuilder({ page }).analyze()

    expect(results.violations).toEqual([])
  })
})
