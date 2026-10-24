import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import Home from './page'

const pushMock = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}))

describe('Home (검색 폼)', () => {
  beforeEach(() => {
    pushMock.mockClear()
  })

  it('주문 ID 입력란과 조회 버튼을 렌더링한다', () => {
    render(<Home />)
    expect(screen.getByPlaceholderText('주문 ID')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조회' })).toBeInTheDocument()
  })

  it('주문 ID를 입력하고 조회를 누르면 결과 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    render(<Home />)

    await user.type(screen.getByPlaceholderText('주문 ID'), 'order-123')
    await user.click(screen.getByRole('button', { name: '조회' }))

    expect(pushMock).toHaveBeenCalledWith('/orders/order-123')
  })

  it('주문 ID가 빈 값이면 이동하지 않는다', async () => {
    const user = userEvent.setup()
    render(<Home />)

    await user.click(screen.getByRole('button', { name: '조회' }))

    expect(pushMock).not.toHaveBeenCalled()
  })
})
