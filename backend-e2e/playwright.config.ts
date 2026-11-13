import { defineConfig } from '@playwright/test'

// 백엔드 E2E 설정. Playwright의 HTTP 요청 기능(request)만 쓰므로 브라우저·프론트 서버를 띄우지 않는다.
// 시나리오 명세: SCENARIOS.md / 원칙: .claude/skills/e2e
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  // E2E 전에 health·Debezium 커넥터를 확인한다. 실패하면 E2E를 돌리지 않는다 (E2E_SKIP_PREFLIGHT=1로 생략 가능)
  globalSetup: './preflight.ts',
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    // 결과 보고용: 오간 HTTP 요청·응답을 trace 뷰어로 단계별 확인할 수 있게 항상 기록
    trace: 'on',
  },
})
