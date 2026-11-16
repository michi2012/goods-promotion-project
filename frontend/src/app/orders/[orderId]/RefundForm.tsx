'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { clientA } from '@/lib/api/client-a'
import { formatOrderStatus } from '@/lib/format'

// 주문 상세에서 PAID 주문의 환불을 요청한다 (docs/specs/user-refund.md "화면").
// 프론트에 로그인이 없어 로그인 ID를 직접 입력받아 X-User-Id로 보낸다 (구매 화면과 같은 수준의 로컬 데모 — 위조 가능).
// 환불 가능 여부(본인·결제 완료·7일 이내)는 serverA가 판단하고, 거절 문구를 그대로 보여준다.
export function RefundForm({ orderId }: { orderId: string }) {
  const router = useRouter()
  const [loginId, setLoginId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleRefund() {
    if (!window.confirm('이 주문 전체를 환불할까요? 환불한 상품은 다시 구매할 수 없습니다.')) {
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const { data, error: apiError } = await clientA.POST('/api/v1/orders/{orderId}/refund', {
        params: { path: { orderId }, header: { 'X-User-Id': loginId } },
      })
      if (apiError) {
        setError(apiError.message ?? '환불 요청이 거절되었습니다.')
        return
      }
      setResult(formatOrderStatus(data.status))
      router.refresh() // 위쪽 주문 상태(Server Component)도 다시 읽는다
    } catch {
      setError('환불 요청 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (result) {
    return <p className="text-sm">환불이 접수되었습니다: <span className="font-medium">{result}</span></p>
  }

  return (
    <div className="flex flex-col gap-3 border-t pt-4">
      <p className="text-sm font-medium">환불 요청</p>
      <Input placeholder="로그인 ID" value={loginId} onChange={(e) => setLoginId(e.target.value)} />
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button type="button" variant="outline" onClick={handleRefund} disabled={submitting || loginId.trim() === ''}>
        {submitting ? '환불 요청 중...' : '환불 요청'}
      </Button>
    </div>
  )
}
