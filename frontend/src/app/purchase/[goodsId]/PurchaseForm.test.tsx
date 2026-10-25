import { Suspense, act } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { PurchaseForm } from './PurchaseForm'

const pushMock = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}))

async function renderForm() {
  let utils!: ReturnType<typeof render>
  // use()가 params Promise를 풀면서 생기는 React 상태 업데이트를 테스트가 보기 전에 flush한다.
  await act(async () => {
    utils = render(
      <Suspense fallback={null}>
        <PurchaseForm params={Promise.resolve({ goodsId: '2' })} />
      </Suspense>,
    )
  })
  return utils
}

async function fillRequiredFields(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByPlaceholderText('결제 수단', {}, { timeout: 5000 }), 'CARD')
  await user.type(screen.getByPlaceholderText('배송지 주소'), '서울시 강남구')
  await user.type(screen.getByPlaceholderText('우편번호'), '06236')
  await user.type(screen.getByPlaceholderText('전화번호'), '010-1234-5678')
  await user.type(screen.getByPlaceholderText('이메일'), 'test@example.com')
}

describe('PurchaseForm', () => {
  beforeEach(() => {
    pushMock.mockClear()
  })

  it('구매 폼 필드를 렌더링한다', async () => {
    await renderForm()
    expect(screen.getByPlaceholderText('결제 수단')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '구매하기' })).toBeInTheDocument()
  })

  it('구매에 성공하면 주문 상태 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    await renderForm()

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
    await renderForm()

    await fillRequiredFields(user)
    await user.click(screen.getByRole('button', { name: '구매하기' }))

    expect(
      await screen.findByText('구매 요청이 거부되었습니다. 입력값을 확인해주세요.'),
    ).toBeInTheDocument()
    expect(pushMock).not.toHaveBeenCalled()
  })
})
