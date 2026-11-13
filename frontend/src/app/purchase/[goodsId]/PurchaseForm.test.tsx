import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import type { components } from '@/api/generated/server-a-types'
import { PurchaseForm } from './PurchaseForm'

const pushMock = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}))

function renderForm() {
  return render(<PurchaseForm goodsId="2" unitPrice={15000} />)
}

async function fillRequiredFields(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('결제 수단'), 'CARD')
  await user.type(screen.getByPlaceholderText('배송지 주소'), '서울시 강남구')
  await user.type(screen.getByPlaceholderText('우편번호'), '06236')
  await user.type(screen.getByPlaceholderText('전화번호'), '010-1234-5678')
  await user.type(screen.getByPlaceholderText('이메일'), 'test@example.com')
}

describe('PurchaseForm', () => {
  beforeEach(() => {
    pushMock.mockClear()
  })

  it('구매 폼 필드를 렌더링한다', () => {
    renderForm()
    expect(screen.getByPlaceholderText('결제 수단')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '구매하기' })).toBeInTheDocument()
  })

  it('기본 수량 1개의 예상 결제 금액을 표시한다', () => {
    renderForm()
    expect(screen.getByText('15,000원')).toBeInTheDocument()
  })

  it('수량을 바꾸면 단가 × 수량으로 예상 결제 금액이 바뀐다', async () => {
    const user = userEvent.setup()
    renderForm()

    const quantity = screen.getByPlaceholderText('수량')
    await user.clear(quantity)
    await user.type(quantity, '3')

    expect(screen.getByText('45,000원')).toBeInTheDocument()
  })

  it('수량이 비어 있으면 예상 결제 금액을 표시하지 않는다', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.clear(screen.getByPlaceholderText('수량'))

    expect(screen.queryByText(/예상 결제 금액/)).not.toBeInTheDocument()
  })

  it('구매 요청 본문에는 금액을 보내지 않는다 (금액은 서버가 계산)', async () => {
    let requestBody: Record<string, unknown> | undefined
    server.use(
      http.post('*/api-a/api/v1/promotions/purchase', async ({ request }) => {
        requestBody = (await request.json()) as Record<string, unknown>
        const body = {
          orderId: 'test-order-id',
          message: '처리 대기중입니다. 잠시 뒤 확인해주세요.',
        } satisfies components['schemas']['PurchaseResponse']
        return HttpResponse.json(body, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderForm()

    await fillRequiredFields(user)
    await user.click(screen.getByRole('button', { name: '구매하기' }))

    await waitFor(() => expect(requestBody).toBeDefined())
    expect(requestBody).toMatchObject({ goodsId: 2, quantity: 1 })
    expect(requestBody).not.toHaveProperty('price')
    expect(requestBody).not.toHaveProperty('amount')
    expect(requestBody).not.toHaveProperty('totalAmount')
  })

  it('구매에 성공하면 주문 상태 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    renderForm()

    await fillRequiredFields(user)
    await user.click(screen.getByRole('button', { name: '구매하기' }))

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith('/orders/test-order-id'))
  })

  it('구매 요청이 거부되면 에러 메시지를 표시한다', async () => {
    server.use(
      http.post('*/api-a/api/v1/promotions/purchase', () =>
        HttpResponse.json({ message: '재고 부족' }, { status: 400 }),
      ),
    )
    const user = userEvent.setup()
    renderForm()

    await fillRequiredFields(user)
    await user.click(screen.getByRole('button', { name: '구매하기' }))

    expect(
      await screen.findByText('구매 요청이 거부되었습니다. 입력값을 확인해주세요.'),
    ).toBeInTheDocument()
    expect(pushMock).not.toHaveBeenCalled()
  })
})
