'use client'

import { use, useState } from 'react'
import { useRouter } from 'next/navigation'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { clientA } from '@/lib/api/client-a'

// 학습 목적 범위 — 로그인/인증이 없어 하드코딩. clientIp도 서버가 유도해야 할 값이라
// 실제 입력 필드로 받지 않고 더미값으로 채운다 (docs/context.md 참조).
const HARDCODED_USER_ID = 1
const HARDCODED_CLIENT_IP = '127.0.0.1'

type FormState = {
  quantity: string
  paymentMethod: string
  shippingAddress: string
  zipCode: string
  phoneNumber: string
  email: string
  deliveryMemo: string
}

const initialState: FormState = {
  quantity: '1',
  paymentMethod: '',
  shippingAddress: '',
  zipCode: '',
  phoneNumber: '',
  email: '',
  deliveryMemo: '',
}

export function PurchaseForm({ params }: { params: Promise<{ goodsId: string }> }) {
  const { goodsId } = use(params)
  const router = useRouter()
  const [form, setForm] = useState<FormState>(initialState)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function update<K extends keyof FormState>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function handleSubmit() {
    setError(null)
    setSubmitting(true)
    try {
      const { data, error: apiError } = await clientA.POST('/api/v1/promotions/purchase', {
        body: {
          userId: HARDCODED_USER_ID,
          goodsId: Number(goodsId),
          quantity: Number(form.quantity),
          paymentMethod: form.paymentMethod,
          shippingAddress: form.shippingAddress,
          zipCode: form.zipCode,
          phoneNumber: form.phoneNumber,
          email: form.email,
          deliveryMemo: form.deliveryMemo || undefined,
          clientIp: HARDCODED_CLIENT_IP,
        },
      })

      if (apiError) {
        setError('구매 요청이 거부되었습니다. 입력값을 확인해주세요.')
        return
      }

      router.push(`/orders/${data.orderId}`)
    } catch {
      setError('구매 요청 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex flex-col gap-3 border-t pt-4">
      <p className="text-sm font-medium">구매하기</p>
      <Input
        placeholder="수량"
        type="number"
        min={1}
        value={form.quantity}
        onChange={(e) => update('quantity', e.target.value)}
      />
      <Input
        placeholder="결제 수단"
        value={form.paymentMethod}
        onChange={(e) => update('paymentMethod', e.target.value)}
      />
      <Input
        placeholder="배송지 주소"
        value={form.shippingAddress}
        onChange={(e) => update('shippingAddress', e.target.value)}
      />
      <Input
        placeholder="우편번호"
        value={form.zipCode}
        onChange={(e) => update('zipCode', e.target.value)}
      />
      <Input
        placeholder="전화번호"
        value={form.phoneNumber}
        onChange={(e) => update('phoneNumber', e.target.value)}
      />
      <Input
        placeholder="이메일"
        value={form.email}
        onChange={(e) => update('email', e.target.value)}
      />
      <Input
        placeholder="배송 메모 (선택)"
        value={form.deliveryMemo}
        onChange={(e) => update('deliveryMemo', e.target.value)}
      />
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button type="button" onClick={handleSubmit} disabled={submitting}>
        {submitting ? '구매 처리 중...' : '구매하기'}
      </Button>
    </div>
  )
}
