import { describe, it, expect } from 'vitest'
import { formatKrw, formatOrderStatus } from './format'

describe('formatOrderStatus', () => {
  it('환불 상태는 사용자 문구로 바꾼다', () => {
    expect(formatOrderStatus('REFUND_REQUESTED')).toBe('환불 처리 중')
    expect(formatOrderStatus('REFUNDED')).toBe('환불 완료')
  })

  it('그 밖의 상태는 원문 그대로 둔다', () => {
    expect(formatOrderStatus('PAID')).toBe('PAID')
    expect(formatOrderStatus('PENDING')).toBe('PENDING')
  })
})

describe('formatKrw', () => {
  it('천 단위 구분 기호와 원을 붙인다', () => {
    expect(formatKrw(15000)).toBe('15,000원')
  })

  it('0원도 표시한다', () => {
    expect(formatKrw(0)).toBe('0원')
  })
})
