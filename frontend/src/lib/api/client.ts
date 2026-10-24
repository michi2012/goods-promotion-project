import createClient from 'openapi-fetch'
import type { paths } from '@/api/generated/types'

const baseUrl =
  process.env.NEXT_PUBLIC_API_MODE === 'mock'
    ? 'http://localhost:4010'
    : 'http://localhost:8081'

export const client = createClient<paths>({ baseUrl })
