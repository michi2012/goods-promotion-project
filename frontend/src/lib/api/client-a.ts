import createClient from 'openapi-fetch'
import type { paths } from '@/api/generated/server-a-types'

// Server Component(Node)는 서버-서버 호출이라 CORS 영향이 없어 serverA를 직접 가리킨다.
// Client Component(브라우저)는 serverA에 CORS 설정이 없어서 next.config.ts의 rewrites로
// same-origin(`/api-a`)을 거치게 한다. 상대경로 문자열만 넘기면 jsdom(Vitest) 테스트 환경의
// fetch가 파싱하지 못해 예외를 던지므로, window.location.origin을 붙여 항상 절대 URL로 만든다.
const SERVER_A_URL = process.env.NEXT_PUBLIC_SERVER_A_BASE_URL ?? 'http://localhost:8080'
const baseUrl = typeof window === 'undefined' ? SERVER_A_URL : `${window.location.origin}/api-a`

export const clientA = createClient<paths>({ baseUrl })
