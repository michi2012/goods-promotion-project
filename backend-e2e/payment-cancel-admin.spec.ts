import { test, expect } from '@playwright/test'

// 시나리오 "PG 취소 실패 관리자 API" (SCENARIOS.md 시나리오 2)
//   ① 역할 없이 목록 조회 → 403 ② 일반 사용자 역할 → 403 ③ 관리자 → 200 + 배열 ④ 기록 없는 주문 재처리 → 404
// X-User-Role은 운영에서 게이트웨이가 JWT를 검증해 넣는다. 여기서는 serverC를 직접 호출해 역할 검사만 확인한다.
// 실패 → 자동 재시도 → 소진 흐름은 serverC 통합 테스트(PaymentCancelRetryIntegrationTest)가 담당한다.
const SERVER_C = process.env.E2E_SERVER_C_URL ?? 'http://localhost:8082'
const BASE = `${SERVER_C}/api/v1/payments/admin/cancel-failures`

test('PG 취소 실패 관리자 API: 관리자만 조회·재처리할 수 있다', async ({ request }) => {
  const evidence = (name: string, body: unknown) =>
    test.info().attach(name, { body: JSON.stringify(body, null, 2), contentType: 'application/json' })

  await test.step('① 역할 헤더 없이 목록 조회 → 403', async () => {
    const res = await request.get(BASE)
    expect(res.status()).toBe(403)
  })

  await test.step('② 일반 사용자 역할로 목록 조회 → 403', async () => {
    const res = await request.get(BASE, { headers: { 'X-User-Role': 'ROLE_USER' } })
    expect(res.status()).toBe(403)
  })

  await test.step('③ 관리자 역할로 목록 조회 → 200 + 배열', async () => {
    const res = await request.get(`${BASE}?status=EXHAUSTED`, { headers: { 'X-User-Role': 'ROLE_ADMIN' } })
    expect(res.status()).toBe(200)
    const body = await res.json()
    expect(Array.isArray(body)).toBeTruthy()
    await evidence('③ 관리자 목록 응답', body)
  })

  await test.step('④ 실패 기록 없는 주문 재처리 → 404', async () => {
    const res = await request.post(`${BASE}/e2e-no-such-order-${Date.now()}/retry`, {
      headers: { 'X-User-Role': 'ROLE_ADMIN' },
    })
    expect(res.status()).toBe(404)
  })
})
