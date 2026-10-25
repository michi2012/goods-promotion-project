import { Suspense } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { clientA } from '@/lib/api/client-a'
import { PurchaseForm } from './PurchaseForm'

export default function GoodsDetailPage({
  params,
}: {
  params: Promise<{ goodsId: string }>
}) {
  return (
    <>
      <h1 className="sr-only">상품 조회 결과</h1>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>상품 조회 결과</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <Suspense fallback={<p className="text-sm text-muted-foreground">조회 중...</p>}>
            <GoodsInfo params={params} />
          </Suspense>
          <Suspense fallback={null}>
            <PurchaseForm params={params} />
          </Suspense>
        </CardContent>
      </Card>
    </>
  )
}

async function GoodsInfo({ params }: { params: Promise<{ goodsId: string }> }) {
  const { goodsId } = await params

  try {
    const { data, error } = await clientA.GET('/api/v1/goods/{goodsId}', {
      params: { path: { goodsId: Number(goodsId) } },
    })

    if (error) {
      return <p className="text-sm text-destructive">상품을 찾을 수 없습니다.</p>
    }

    return (
      <p className="text-sm">
        {data.name} — 재고 <span className="font-medium">{data.stock}</span>개
      </p>
    )
  } catch {
    return <p className="text-sm text-destructive">상품을 찾을 수 없습니다.</p>
  }
}
