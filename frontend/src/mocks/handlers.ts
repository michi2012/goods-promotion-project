import { http, HttpResponse } from 'msw'

export const handlers = [
  http.get('/api/v1/orders/:orderId/status', ({ params }) => {
    const { orderId } = params
    if (orderId === 'order-123') {
      return HttpResponse.json({ orderId, status: 'SHIPPED' })
    }
    return HttpResponse.json({ orderId, status: 'NOT_FOUND' })
  }),
]
