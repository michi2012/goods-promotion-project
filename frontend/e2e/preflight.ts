// E2E 사전 점검 (Playwright globalSetup). 환경이 준비되지 않았으면 E2E를 돌리지 않고 원인을 알려준다.
// 셸·Docker CLI에 의존하지 않도록 HTTP로만 확인한다. health 응답에는 DB·Redis 상태도 포함된다.
// 원칙: .claude/skills/e2e (SKILL.md 4장), 점검 대상: .claude/skills/e2e/project.md

// 첫 요청·GC 등 일시적 지연으로 점검이 E2E를 막지 않도록 넉넉한 타임아웃 + 실패 시 1회 재확인
const TIMEOUT_MS = 10_000
const ATTEMPTS = 2

const SERVERS: Array<[name: string, baseUrl: string]> = [
  ['serverA', process.env.E2E_SERVER_A_URL ?? 'http://localhost:8080'],
  ['serverB', process.env.E2E_SERVER_B_URL ?? 'http://localhost:8081'],
  ['serverC', process.env.E2E_SERVER_C_URL ?? 'http://localhost:8082'],
]

const KAFKA_CONNECT_URL = process.env.E2E_KAFKA_CONNECT_URL ?? 'http://localhost:8083'
const CONNECTORS = ['promotion-outbox-connector', 'payment-outbox-connector']

type ConnectorStatus = { connector?: { state?: string }; tasks?: Array<{ id: number; state: string }> }

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return (await res.json()) as T
}

async function checkServer(name: string, baseUrl: string): Promise<string | null> {
  const url = `${baseUrl}/actuator/health`
  try {
    const body = await getJson<{ status?: string }>(url)
    return body.status === 'UP' ? null : `${name} health=${body.status} (${url})`
  } catch (e) {
    return `${name} 응답 없음 (${url}): ${(e as Error).message}`
  }
}

async function checkConnector(name: string): Promise<string | null> {
  const url = `${KAFKA_CONNECT_URL}/connectors/${name}/status`
  try {
    const body = await getJson<ConnectorStatus>(url)
    const tasks = body.tasks ?? []
    const notRunning = tasks.filter((t) => t.state !== 'RUNNING').map((t) => `task${t.id}=${t.state}`)
    if (body.connector?.state !== 'RUNNING' || tasks.length === 0 || notRunning.length > 0) {
      return `Debezium 커넥터 ${name}: connector=${body.connector?.state}, tasks=[${notRunning.join(', ') || tasks.length}]`
    }
    return null
  } catch (e) {
    return `Debezium 커넥터 ${name} 확인 불가 (${url}): ${(e as Error).message}`
  }
}

async function withRetry(check: () => Promise<string | null>): Promise<string | null> {
  let problem: string | null = null
  for (let i = 0; i < ATTEMPTS; i++) {
    problem = await check()
    if (problem === null) return null
  }
  return problem
}

export default async function globalSetup(): Promise<void> {
  if (process.env.E2E_SKIP_PREFLIGHT === '1') return

  const results = await Promise.all([
    ...SERVERS.map(([name, url]) => withRetry(() => checkServer(name, url))),
    ...CONNECTORS.map((name) => withRetry(() => checkConnector(name))),
  ])
  const problems = results.filter((r): r is string => r !== null)

  if (problems.length > 0) {
    throw new Error(
      `[preflight] E2E 환경이 준비되지 않아 E2E를 실행하지 않습니다.\n` +
        problems.map((p) => `  - ${p}`).join('\n') +
        `\n환경 문제이므로 코드를 고치지 말고 인프라·앱·커넥터를 먼저 기동하세요.`,
    )
  }
}
