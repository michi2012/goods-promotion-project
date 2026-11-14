package promotion.serverC.service;

// PG 취소 응답. 이미 취소된 거래를 다시 취소하면 ALREADY_CANCELLED — 우리 쪽에서는 둘 다 "취소 완료"로 본다.
// 취소 실패는 예외(PgPaymentException 등)로 던진다.
public enum PgCancelResult {
    CANCELLED,
    ALREADY_CANCELLED
}
