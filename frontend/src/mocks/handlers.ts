import { http, HttpResponse } from 'msw'
import type { components } from '@/api/generated/server-a-types'

export const handlers = [
  http.post('*/api-a/api/v1/promotions/purchase', async () => {
    const body = {
      orderId: 'test-order-id',
      message: '처리 대기중입니다. 잠시 뒤 확인해주세요.',
    } satisfies components['schemas']['PurchaseResponse']
    return HttpResponse.json(body, { status: 202 })
  }),
]
