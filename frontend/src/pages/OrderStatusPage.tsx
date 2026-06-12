import { useState } from 'react'
import { useGetOrderStatus } from '@/api/generated/order-query-controller/order-query-controller'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

export function OrderStatusPage() {
  const [orderId, setOrderId] = useState('')
  const [searchedOrderId, setSearchedOrderId] = useState('')

  const { data, isFetching, isError } = useGetOrderStatus(searchedOrderId, {
    query: { enabled: searchedOrderId !== '' },
  })

  return (
    <div className="flex min-h-svh flex-col">
      <header className="border-b p-4">
        <span className="text-lg font-semibold">Promotion</span>
      </header>
      <main className="flex flex-1 items-center justify-center p-8">
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
              <Button type="button" onClick={() => setSearchedOrderId(orderId)}>
                조회
              </Button>
            </div>
            {isFetching && <p className="text-sm text-muted-foreground">조회 중...</p>}
            {isError && <p className="text-sm text-destructive">조회 중 오류가 발생했습니다.</p>}
            {data && (
              <p className="text-sm">
                주문 {data.orderId}의 상태: <span className="font-medium">{data.status}</span>
              </p>
            )}
          </CardContent>
        </Card>
      </main>
      <footer className="border-t p-4 text-center text-sm text-muted-foreground">
        © 2026 Promotion
      </footer>
    </div>
  )
}
