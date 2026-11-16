import { test, expect, type APIRequestContext } from '@playwright/test'

// 시나리오 "사용자 환불" (SCENARIOS.md 시나리오 3과 단계 번호를 맞춘다)
//   ① 사용자 생성 → ② 상품 생성(재고 5) → ③ 구매 2개 → ④ 주문 PAID → ⑤ 재고 3
//   → ⑥ 없는 로그인 ID 환불 404 → ⑦ 환불 동시 2회(둘 다 200) → ⑧ 주문 REFUNDED → ⑨ 재고 5(정확히 1회 복구) → ⑩ 재구매 거절
// serverA(환불 접수) → Outbox CDC → serverC(PG 취소) → payment-cancelled → serverA(환불 완료·재고 복구) → serverB(상태 뷰)
// 최종 결과를 각 서버의 공개 API로 확인한다 (DB 직접 조회 없음 — .claude/skills/e2e 블랙박스 원칙).
// "PG 취소 1회"는 블랙박스로 볼 수 없어 재고가 정확히 1회 복구됐는지로 확인한다 (PG 멱등은 serverC 단위 테스트).
const SERVER_A = process.env.E2E_SERVER_A_URL ?? 'http://localhost:8080'
const SERVER_B = process.env.E2E_SERVER_B_URL ?? 'http://localhost:8081'
const USER_SERVICE = process.env.E2E_USER_SERVICE_URL ?? 'http://localhost:8086'
const FLOW_TIMEOUT_MS = Number(process.env.E2E_FLOW_TIMEOUT_MS ?? 60_000)
const POLL_INTERVALS = [1_000, 2_000, 3_000]

// 모의 PG는 1% 확률로 결제를 거절한다 (구매 흐름과 같은 이유로 1회 재시도, 재시도마다 데이터를 새로 만든다)
test.describe.configure({ retries: 1 })

async function orderStatus(request: APIRequestContext, orderId: string): Promise<string | undefined> {
  const res = await request.get(`${SERVER_B}/api/v1/orders/${orderId}/status`)
  return ((await res.json()) as { status?: string }).status
}

async function goodsStock(request: APIRequestContext, goodsId: number): Promise<number> {
  const res = await request.get(`${SERVER_A}/api/v1/goods/${goodsId}`)
  expect(res.ok(), `상품 조회 실패: HTTP ${res.status()}`).toBeTruthy()
  return ((await res.json()) as { stock: number }).stock
}

test('사용자 환불: 구매 → 환불 동시 2회 → REFUNDED·재고 1회 복구 → 재구매 거절', async ({ request }) => {
  const quantity = 2
  const runId = Date.now()
  const evidence = (name: string, body: unknown) =>
    test.info().attach(name, { body: JSON.stringify(body, null, 2), contentType: 'application/json' })

  const user = await test.step('① 사용자 생성 (user-service 내부 API)', async () => {
    const res = await request.post(`${USER_SERVICE}/internal/api/users`, {
      data: {
        email: `e2e-refund-${runId}@example.com`,
        password: 'e2e-password-1234',
        username: `e2e-r${runId}`, // user-service는 username도 유니크·VARCHAR(20) — 실행마다 고유한 18자
        phoneNumber: `010${String(runId).slice(-8)}`,
      },
    })
    expect(res.status(), `사용자 생성 실패: HTTP ${res.status()}`).toBe(201)
    const body = (await res.json()) as { id: number; userId: string }
    await evidence('① 사용자 생성 응답', body)
    return body
  })

  const goods = await test.step('② 상품 생성 — 재고 5 (serverA)', async () => {
    const res = await request.post(`${SERVER_A}/api/v1/goods`, {
      data: { name: `e2e-refund-${runId}`, price: 15_000, stock: 5 },
    })
    expect(res.ok(), `상품 생성 실패: HTTP ${res.status()}`).toBeTruthy()
    const body = (await res.json()) as { id: number }
    await evidence('② 상품 생성 응답', body)
    return body
  })

  const purchase = {
    userId: user.id,
    goodsId: goods.id,
    quantity,
    paymentMethod: 'CARD',
    shippingAddress: '서울시 강남구 테헤란로 1',
    zipCode: '06236',
    phoneNumber: '010-0000-0000',
    email: 'e2e@example.com',
    clientIp: '127.0.0.1',
  }

  const orderId: string = await test.step('③ 구매 2개 (serverA)', async () => {
    const res = await request.post(`${SERVER_A}/api/v1/promotions/purchase`, { data: purchase })
    expect(res.status(), '구매 접수는 202').toBe(202)
    const body = (await res.json()) as { orderId: string }
    await evidence('③ 구매 접수 응답', body)
    return body.orderId
  })

  await test.step('④ 주문 상태 PAID (serverB)', async () => {
    await expect
      .poll(() => orderStatus(request, orderId), {
        message: `결제 완료 대기 (orderId=${orderId})`,
        timeout: FLOW_TIMEOUT_MS,
        intervals: POLL_INTERVALS,
      })
      .toBe('PAID')
  })

  await test.step('⑤ 구매 후 재고 3 (serverA)', async () => {
    const stock = await goodsStock(request, goods.id)
    await evidence('⑤ 구매 후 재고', { goodsId: goods.id, stock })
    expect(stock).toBe(5 - quantity)
  })

  await test.step('⑥ 없는 로그인 ID로 환불 → 404 ORDER_NOT_FOUND (serverA → user-service)', async () => {
    const res = await request.post(`${SERVER_A}/api/v1/orders/${orderId}/refund`, {
      headers: { 'X-User-Id': `e2e-nobody-${runId}` },
    })
    const body = await res.json()
    await evidence('⑥ 없는 로그인 ID 환불 응답', body)
    expect(res.status()).toBe(404)
    expect(body).toMatchObject({ error: 'ORDER_NOT_FOUND' })
  })

  await test.step('⑦ 환불 동시 2회 → 둘 다 200 (serverA)', async () => {
    const refund = () =>
      request.post(`${SERVER_A}/api/v1/orders/${orderId}/refund`, { headers: { 'X-User-Id': user.userId } })
    const [first, second] = await Promise.all([refund(), refund()])
    const bodies = [await first.json(), await second.json()]
    await evidence('⑦ 환불 동시 2회 응답', { statuses: [first.status(), second.status()], bodies })
    expect([first.status(), second.status()]).toEqual([200, 200])
    for (const body of bodies) {
      expect(body.orderId).toBe(orderId)
      expect(['REFUND_REQUESTED', 'REFUNDED']).toContain(body.status)
    }
  })

  await test.step('⑧ 주문 상태 REFUNDED (serverB)', async () => {
    await expect
      .poll(() => orderStatus(request, orderId), {
        message: `환불 완료 대기 (orderId=${orderId})`,
        timeout: FLOW_TIMEOUT_MS,
        intervals: POLL_INTERVALS,
      })
      .toBe('REFUNDED')
  })

  await test.step('⑨ 재고 5 — 정확히 1회 복구 (serverA)', async () => {
    // 재고 복구는 REFUNDED 전이와 같은 트랜잭션이라, serverB에 REFUNDED가 보이면 이미 커밋돼 있다
    const stock = await goodsStock(request, goods.id)
    await evidence('⑨ 환불 후 재고', { goodsId: goods.id, stock })
    expect(stock).toBe(5)
  })

  await test.step('⑩ 같은 상품 재구매 → 400 DUPLICATE_ORDER (P-12: 환불해도 구매 표시 유지)', async () => {
    const res = await request.post(`${SERVER_A}/api/v1/promotions/purchase`, { data: purchase })
    const body = await res.json()
    await evidence('⑩ 재구매 응답', body)
    expect(res.status()).toBe(400)
    expect(body).toMatchObject({ error: 'DUPLICATE_ORDER' })
  })
})
