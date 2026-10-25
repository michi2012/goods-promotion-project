import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import PurchaseSearch from './page'

const pushMock = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}))

describe('PurchaseSearch (상품 조회 검색 폼)', () => {
  beforeEach(() => {
    pushMock.mockClear()
  })

  it('상품 ID 입력란과 조회 버튼을 렌더링한다', () => {
    render(<PurchaseSearch />)
    expect(screen.getByPlaceholderText('상품 ID')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조회' })).toBeInTheDocument()
  })

  it('상품 ID를 입력하고 조회를 누르면 상품 조회 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    render(<PurchaseSearch />)

    await user.type(screen.getByPlaceholderText('상품 ID'), '2')
    await user.click(screen.getByRole('button', { name: '조회' }))

    expect(pushMock).toHaveBeenCalledWith('/purchase/2')
  })

  it('상품 ID가 빈 값이면 이동하지 않는다', async () => {
    const user = userEvent.setup()
    render(<PurchaseSearch />)

    await user.click(screen.getByRole('button', { name: '조회' }))

    expect(pushMock).not.toHaveBeenCalled()
  })
})
