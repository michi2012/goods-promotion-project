// 원 단위 정수 금액을 "15,000원" 형식으로 표시한다. 표시 전용 — 계산·청구 금액은 서버가 정한다.
export function formatKrw(amount: number): string {
  return `${new Intl.NumberFormat('ko-KR').format(amount)}원`
}

// 주문 상태를 화면 문구로. 환불 상태만 사용자 문구로 바꾸고 나머지는 원문 그대로 (docs/specs/user-refund.md "화면").
// PG 취소 실패·재시도 중에도 사용자에게는 "환불 처리 중"이다 (P-8).
const ORDER_STATUS_LABELS: Record<string, string> = {
  REFUND_REQUESTED: '환불 처리 중',
  REFUNDED: '환불 완료',
}

export function formatOrderStatus(status: string): string {
  return ORDER_STATUS_LABELS[status] ?? status
}
