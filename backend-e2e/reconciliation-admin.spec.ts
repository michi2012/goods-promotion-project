import { test, expect, APIRequestContext } from '@playwright/test'

// 시나리오 "PG 대사 수동 실행 관리자 API" (SCENARIOS.md 시나리오 4, 명세 docs/specs/pg-reconciliation-batch.md)
//   ① 관리자 아님 → 403 ② 오늘 → 400 ③ PG 파일 없는 과거 날짜 → 202 NEW
//   ④ Job 실패 → 실패 메트릭 +1 ⑤ 같은 날짜 다시 → 202 RESTART ⑥ 재시작도 실패 → 실패 메트릭 +1
// 실행은 비동기라 결과는 serverC가 노출한 메트릭(/actuator/prometheus)으로 확인한다 (실행 상태 조회 API는 명세 범위 밖).
// PG 파일을 컨테이너에 넣을 수단이 없어 성공·불일치 경로는 serverC Testcontainers 테스트(ReconciliationMetricsTest 등)가 담당한다.
const SERVER_C = process.env.E2E_SERVER_C_URL ?? 'http://localhost:8082'
const BASE = `${SERVER_C}/api/v1/payments/admin/reconciliations`
const ADMIN = { 'X-User-Role': 'ROLE_ADMIN' }
const TIMEOUT = Number(process.env.E2E_FLOW_TIMEOUT_MS ?? 60_000)

// PG 파일이 있을 리 없는 과거 날짜를 실행마다 다르게 고른다 (2000-01-01 ~ 2019-12-31)
function randomPastDate(): string {
  const start = Date.UTC(2000, 0, 1)
  const days = Math.floor(Math.random() * 7300)
  return new Date(start + days * 86_400_000).toISOString().slice(0, 10)
}

function todayKst(): string {
  return new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10)
}

async function failedCount(request: APIRequestContext): Promise<number> {
  const res = await request.get(`${SERVER_C}/actuator/prometheus`)
  expect(res.ok()).toBeTruthy()
  const text = await res.text()
  const values = [...text.matchAll(/^business_reconciliation_job_failed_total(?:\{[^}]*\})? (\S+)$/gm)]
  expect(values.length, 'business_reconciliation_job_failed_total 메트릭이 노출돼야 한다').toBeGreaterThan(0)
  return values.reduce((sum, m) => sum + Number(m[1]), 0)
}

test('PG 대사 수동 실행: 관리자만, 어제까지, 실패하면 다시 실행 시 재시작', async ({ request }) => {
  const evidence = (name: string, body: unknown) =>
    test.info().attach(name, { body: JSON.stringify(body, null, 2), contentType: 'application/json' })
  const date = randomPastDate()

  await test.step('① 관리자 역할 없이 실행 → 403', async () => {
    expect((await request.post(`${BASE}?date=${date}`)).status()).toBe(403)
    expect((await request.post(`${BASE}?date=${date}`, { headers: { 'X-User-Role': 'ROLE_USER' } })).status()).toBe(403)
  })

  await test.step('② 오늘(KST) 날짜 → 400 (그날 PG 파일은 다음 날 생성)', async () => {
    expect((await request.post(`${BASE}?date=${todayKst()}`, { headers: ADMIN })).status()).toBe(400)
  })

  const before = await failedCount(request)
  let firstExecutionId = 0

  await test.step(`③ PG 파일 없는 과거 날짜(${date}) → 202 NEW`, async () => {
    const res = await request.post(`${BASE}?date=${date}`, { headers: ADMIN })
    expect(res.status()).toBe(202)
    const body = await res.json()
    await evidence('③ 첫 실행 응답', body)
    expect(body.mode).toBe('NEW')
    expect(body.jobExecutionId).toBeGreaterThan(0)
    firstExecutionId = body.jobExecutionId
  })

  await test.step('④ PG 파일 없음 → Job FAILED → 실패 메트릭 +1', async () => {
    await expect.poll(() => failedCount(request), { timeout: TIMEOUT, intervals: [500, 1_000, 2_000] })
      .toBe(before + 1)
  })

  await test.step('⑤ 같은 날짜 다시 실행 → 202 RESTART (새 실행 ID)', async () => {
    const res = await request.post(`${BASE}?date=${date}`, { headers: ADMIN })
    expect(res.status()).toBe(202)
    const body = await res.json()
    await evidence('⑤ 재실행 응답', body)
    expect(body.mode).toBe('RESTART')
    expect(body.jobExecutionId).toBeGreaterThan(firstExecutionId)
  })

  await test.step('⑥ 파일이 여전히 없음 → 재시작도 FAILED → 실패 메트릭 +1', async () => {
    await expect.poll(() => failedCount(request), { timeout: TIMEOUT, intervals: [500, 1_000, 2_000] })
      .toBe(before + 2)
    await evidence('④⑥ 실패 메트릭 전후', { before, after: before + 2 })
  })
})
