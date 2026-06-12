import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { OrderStatusPage } from '@/pages/OrderStatusPage'

function renderWithQueryClient() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderStatusPage />
    </QueryClientProvider>,
  )
}

describe('OrderStatusPage', () => {
  it('주문 ID 입력란과 조회 버튼을 렌더링한다', () => {
    renderWithQueryClient()

    expect(screen.getByPlaceholderText('주문 ID')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조회' })).toBeInTheDocument()
  })

  it('주문 ID를 입력하고 조회하면 serverB(MSW 모킹) 응답을 화면에 표시한다', async () => {
    const user = userEvent.setup()
    renderWithQueryClient()

    await user.type(screen.getByPlaceholderText('주문 ID'), 'order-123')
    await user.click(screen.getByRole('button', { name: '조회' }))

    await waitFor(() => {
      expect(screen.getByText(/주문 order-123의 상태/)).toBeInTheDocument()
      expect(screen.getByText('SHIPPED')).toBeInTheDocument()
    })
  })

  it('존재하지 않는 주문 ID는 NOT_FOUND 상태를 표시한다', async () => {
    const user = userEvent.setup()
    renderWithQueryClient()

    await user.type(screen.getByPlaceholderText('주문 ID'), 'unknown-order')
    await user.click(screen.getByRole('button', { name: '조회' }))

    await waitFor(() => {
      expect(screen.getByText('NOT_FOUND')).toBeInTheDocument()
    })
  })
})
