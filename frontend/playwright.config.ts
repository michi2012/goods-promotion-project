import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // E2E 전에 health·Debezium 커넥터를 확인한다. 실패하면 E2E를 돌리지 않는다 (E2E_SKIP_PREFLIGHT=1로 생략 가능)
  globalSetup: './e2e/preflight.ts',
  fullyParallel: true,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
    // 결과 보고용 근거(.claude/skills/e2e 7장): E2E_SCREENSHOTS=1이면 모든 테스트 화면을 리포트에 남긴다
    screenshot: process.env.E2E_SCREENSHOTS === '1' ? 'on' : 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
  },
})
