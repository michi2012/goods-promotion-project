package promotion.serverC.service;

// PG 승인·조회 응답. 실제 PG도 승인 결과와 함께 승인 금액을 돌려주며,
// 우리는 이 금액이 주문 금액과 같은지 검증해야 한다 (금액 위변조·오류 방어).
public record PgTransactionResult(PgTransactionStatus status, Long approvedAmount) {

    public static PgTransactionResult approved(Long approvedAmount) {
        return new PgTransactionResult(PgTransactionStatus.APPROVED, approvedAmount);
    }

    public static PgTransactionResult rejected() {
        return new PgTransactionResult(PgTransactionStatus.REJECTED, null);
    }

    public static PgTransactionResult notFound() {
        return new PgTransactionResult(PgTransactionStatus.NOT_FOUND, null);
    }

    public boolean isApproved() {
        return status == PgTransactionStatus.APPROVED;
    }
}
