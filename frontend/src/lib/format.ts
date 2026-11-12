// 원 단위 정수 금액을 "15,000원" 형식으로 표시한다. 표시 전용 — 계산·청구 금액은 서버가 정한다.
export function formatKrw(amount: number): string {
  return `${new Intl.NumberFormat('ko-KR').format(amount)}원`
}
