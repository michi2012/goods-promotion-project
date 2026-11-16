import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import type { components } from '@/api/generated/server-a-types'
import { RefundForm } from './RefundForm'

const refreshMock = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ refresh: refreshMock }),
}))

const REFUND_URL = '*/api-a/api/v1/orders/order-1/refund'

function refundSucceeds(onRequest?: (request: Request) => void) {
  server.use(
    http.post(REFUND_URL, ({ request }) => {
      onRequest?.(request)
      const body = { orderId: 'order-1', status: 'REFUND_REQUESTED' } satisfies components['schemas']['RefundResponse']
      return HttpResponse.json(body)
    }),
  )
}

describe('RefundForm', () => {
  beforeEach(() => {
    refreshMock.mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('로그인 ID를 입력하기 전에는 환불 버튼이 비활성화된다', () => {
    render(<RefundForm orderId="order-1" />)
    expect(screen.getByRole('button', { name: '환불 요청' })).toBeDisabled()
  })

  it('확인하면 로그인 ID를 X-User-Id로 보내고, 접수되면 "환불 처리 중"을 표시한 뒤 주문 상태를 다시 읽는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    let userIdHeader: string | null = null
    refundSucceeds((request) => {
      userIdHeader = request.headers.get('X-User-Id')
    })
    const user = userEvent.setup()
    render(<RefundForm orderId="order-1" />)

    await user.type(screen.getByPlaceholderText('로그인 ID'), 'login-abc')
    await user.click(screen.getByRole('button', { name: '환불 요청' }))

    expect(await screen.findByText('환불 처리 중')).toBeInTheDocument()
    expect(userIdHeader).toBe('login-abc')
    expect(refreshMock).toHaveBeenCalled()
  })

  it('확인 창에서 취소하면 요청을 보내지 않는다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    let requested = false
    refundSucceeds(() => {
      requested = true
    })
    const user = userEvent.setup()
    render(<RefundForm orderId="order-1" />)

    await user.type(screen.getByPlaceholderText('로그인 ID'), 'login-abc')
    await user.click(screen.getByRole('button', { name: '환불 요청' }))

    await waitFor(() => expect(window.confirm).toHaveBeenCalled())
    expect(requested).toBe(false)
    expect(screen.getByRole('button', { name: '환불 요청' })).toBeInTheDocument()
  })

  it('serverA가 거절하면 응답 문구를 그대로 표시한다 (기간 초과)', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    server.use(
      http.post(REFUND_URL, () => {
        const body = {
          status: 409,
          error: 'REFUND_PERIOD_EXPIRED',
          message: '환불 기간(결제 후 7일)이 지났습니다',
        } satisfies components['schemas']['ErrorResponse']
        return HttpResponse.json(body, { status: 409 })
      }),
    )
    const user = userEvent.setup()
    render(<RefundForm orderId="order-1" />)

    await user.type(screen.getByPlaceholderText('로그인 ID'), 'login-abc')
    await user.click(screen.getByRole('button', { name: '환불 요청' }))

    expect(await screen.findByText('환불 기간(결제 후 7일)이 지났습니다')).toBeInTheDocument()
    expect(refreshMock).not.toHaveBeenCalled()
  })

  it('남의 주문이면 serverA의 "주문을 찾을 수 없습니다"를 표시한다', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    server.use(
      http.post(REFUND_URL, () =>
        HttpResponse.json(
          { status: 404, error: 'ORDER_NOT_FOUND', message: '주문을 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    const user = userEvent.setup()
    render(<RefundForm orderId="order-1" />)

    await user.type(screen.getByPlaceholderText('로그인 ID'), 'someone-else')
    await user.click(screen.getByRole('button', { name: '환불 요청' }))

    expect(await screen.findByText('주문을 찾을 수 없습니다')).toBeInTheDocument()
  })
})
