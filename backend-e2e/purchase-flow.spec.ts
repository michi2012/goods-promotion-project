import { test, expect } from '@playwright/test'

// 시나리오 "구매 흐름" (SCENARIOS.md와 단계 번호를 맞춘다)
//   ① 상품 생성 → ② 구매 2개 → ③ 결제 30,000 KRW PAID → ④ 주문 상태 PAID
// serverA(구매 접수) → Kafka·Outbox CDC → serverC(결제) / serverB(상태 뷰)를 거친 최종 결과를
// 각 서버의 공개 API로 확인한다 (DB 직접 조회 없음 — .claude/skills/e2e 블랙박스 원칙).
const SERVER_A = process.env.E2E_SERVER_A_URL ?? 'http://localhost:8080'
const SERVER_B = process.env.E2E_SERVER_B_URL ?? 'http://localhost:8081'
const SERVER_C = process.env.E2E_SERVER_C_URL ?? 'http://localhost:8082'
const FLOW_TIMEOUT_MS = Number(process.env.E2E_FLOW_TIMEOUT_MS ?? 60_000)
const POLL_INTERVALS = [1_000, 2_000, 3_000]

// 모의 PG(MockPgClient)는 1% 확률로 결제를 거절한다. 거절은 정상 동작이므로 한 번만 재시도한다
// (재시도마다 상품·사용자를 새로 만들어 서로 간섭하지 않는다).
test.describe.configure({ retries: 1 })

test('구매 흐름: 상품 생성 → 2개 구매 → 결제 30,000 KRW PAID → 주문 상태 PAID', async ({ request }) => {
  const unitPrice = 15_000
  const quantity = 2
  const evidence = (name: string, body: unknown) =>
    test.info().attach(name, { body: JSON.stringify(body, null, 2), contentType: 'application/json' })

  const goods = await test.step('① 상품 생성 (serverA)', async () => {
    const res = await request.post(`${SERVER_A}/api/v1/goods`, {
      data: { name: `e2e-api-${Date.now()}`, price: unitPrice, stock: 5 },
    })
    expect(res.ok(), `상품 생성 실패: HTTP ${res.status()}`).toBeTruthy()
    const body = await res.json()
    expect(body.price).toBe(unitPrice)
    await evidence('① 상품 생성 응답', body)
    return body
  })

  const orderId: string = await test.step('② 구매 2개 (serverA)', async () => {
    // 실행마다 고유한 userId — 1인 1회 중복 구매 방어에 걸리지 않게
    const res = await request.post(`${SERVER_A}/api/v1/promotions/purchase`, {
      data: {
        userId: Date.now(),
        goodsId: goods.id,
        quantity,
        paymentMethod: 'CARD',
        shippingAddress: '서울시 강남구 테헤란로 1',
        zipCode: '06236',
        phoneNumber: '010-0000-0000',
        email: 'e2e@example.com',
        clientIp: '127.0.0.1',
      },
    })
    expect(res.status(), '구매 접수는 202').toBe(202)
    const body = await res.json()
    await evidence('② 구매 접수 응답', body)
    return body.orderId
  })

  await test.step('③ 결제 30,000 KRW PAID (serverC)', async () => {
    let payment: unknown
    await expect
      .poll(
        async () => {
          const res = await request.get(`${SERVER_C}/api/v1/payments/${orderId}`)
          payment = res.ok() ? await res.json() : { status: `HTTP ${res.status()}` }
          return payment
        },
        { message: `결제 결과 대기 (orderId=${orderId})`, timeout: FLOW_TIMEOUT_MS, intervals: POLL_INTERVALS },
      )
      .toMatchObject({ status: 'PAID', amount: unitPrice * quantity, currency: 'KRW', quantity })
    await evidence('③ 결제 조회 응답', payment)
  })

  await test.step('④ 주문 상태 PAID (serverB)', async () => {
    let orderStatus: { status?: string } = {}
    await expect
      .poll(
        async () => {
          orderStatus = await (await request.get(`${SERVER_B}/api/v1/orders/${orderId}/status`)).json()
          return orderStatus.status
        },
        { message: `주문 상태 대기 (orderId=${orderId})`, timeout: FLOW_TIMEOUT_MS, intervals: POLL_INTERVALS },
      )
      .toBe('PAID')
    await evidence('④ 주문 상태 응답', orderStatus)
  })
})
