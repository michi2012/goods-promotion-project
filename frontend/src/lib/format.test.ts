import { describe, it, expect } from 'vitest'
import { formatKrw } from './format'

describe('formatKrw', () => {
  it('천 단위 구분 기호와 원을 붙인다', () => {
    expect(formatKrw(15000)).toBe('15,000원')
  })

  it('0원도 표시한다', () => {
    expect(formatKrw(0)).toBe('0원')
  })
})
