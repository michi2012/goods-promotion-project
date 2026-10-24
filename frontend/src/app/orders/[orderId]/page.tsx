import { Suspense } from 'react'
import Link from 'next/link'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { client } from '@/lib/api/client'

export default function OrderStatusPage({
  params,
}: {
  params: Promise<{ orderId: string }>
}) {
  return (
    <>
      <h1 className="sr-only">주문 상태 조회 결과</h1>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>주문 상태 조회 결과</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <Suspense fallback={<p className="text-sm text-muted-foreground">조회 중...</p>}>
            <OrderStatus params={params} />
          </Suspense>
          <Link href="/" className="text-sm underline">
            ← 다시 조회
          </Link>
        </CardContent>
      </Card>
    </>
  )
}

async function OrderStatus({ params }: { params: Promise<{ orderId: string }> }) {
  const { orderId } = await params

  // client.GET의 {data, error}는 HTTP 레벨 에러만 잡는다. 백엔드가 아예 안 떠 있을 때(connection refused)
  // 처럼 fetch 자체가 throw하는 네트워크 레벨 실패는 try/catch로 따로 잡아야
  // Next.js의 기본 에러 화면 대신 이 페이지의 에러 문구가 뜬다.
  try {
    const { data, error } = await client.GET('/api/v1/orders/{orderId}/status', {
      params: { path: { orderId } },
    })

    if (error) {
      return <p className="text-sm text-destructive">조회 중 오류가 발생했습니다.</p>
    }

    return (
      <p className="text-sm">
        주문 {data.orderId}의 상태: <span className="font-medium">{data.status}</span>
      </p>
    )
  } catch {
    return <p className="text-sm text-destructive">조회 중 오류가 발생했습니다.</p>
  }
}
