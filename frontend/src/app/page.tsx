'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export default function Home() {
  const [orderId, setOrderId] = useState('')
  const router = useRouter()

  function handleSearch() {
    if (orderId === '') return
    router.push(`/orders/${orderId}`)
  }

  return (
    <>
      <h1 className="sr-only">주문 상태 조회</h1>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>주문 상태 조회</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex gap-2">
            <Input
              placeholder="주문 ID"
              value={orderId}
              onChange={(e) => setOrderId(e.target.value)}
            />
            <Button type="button" onClick={handleSearch}>
              조회
            </Button>
          </div>
        </CardContent>
      </Card>
    </>
  )
}
