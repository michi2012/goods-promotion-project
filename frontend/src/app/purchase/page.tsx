'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export default function PurchaseSearch() {
  const [goodsId, setGoodsId] = useState('')
  const router = useRouter()

  function handleSearch() {
    if (goodsId === '') return
    router.push(`/purchase/${goodsId}`)
  }

  return (
    <>
      <h1 className="sr-only">상품 조회</h1>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>상품 조회</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex gap-2">
            <Input
              placeholder="상품 ID"
              value={goodsId}
              onChange={(e) => setGoodsId(e.target.value)}
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
