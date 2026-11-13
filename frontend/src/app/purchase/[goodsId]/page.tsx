import { Suspense } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { clientA } from '@/lib/api/client-a'
import { formatKrw } from '@/lib/format'
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
        </CardContent>
      </Card>
    </>
  )
}

async function GoodsInfo({ params }: { params: Promise<{ goodsId: string }> }) {
  const { goodsId } = await params

  // try/catch는 네트워크 호출에만 두고, JSX는 밖에서 만든다 (react-hooks/error-boundaries).
  let goods: Awaited<ReturnType<typeof fetchGoods>> = undefined
  try {
    goods = await fetchGoods(goodsId)
  } catch {
    goods = undefined
  }

  if (!goods) {
    return <p className="text-sm text-destructive">상품을 찾을 수 없습니다.</p>
  }

  // 예상 금액 표시에 단가가 필요하므로, 상품 조회가 성공한 경우에만 구매 폼을 렌더링한다.
  return (
    <>
      <p className="text-sm">
        {goods.name} — <span className="font-medium">{formatKrw(goods.price)}</span> · 재고{' '}
        <span className="font-medium">{goods.stock}</span>개
      </p>
      <PurchaseForm goodsId={goodsId} unitPrice={goods.price} />
    </>
  )
}

async function fetchGoods(goodsId: string) {
  const { data, error } = await clientA.GET('/api/v1/goods/{goodsId}', {
    params: { path: { goodsId: Number(goodsId) } },
  })
  return error ? undefined : data
}
