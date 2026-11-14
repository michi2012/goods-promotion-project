package promotion.serverC.service;

import promotion.serverC.dto.PurchaseMessage;

public interface PgClient {

    // 승인 요청. 승인 여부와 함께 PG가 실제 승인한 금액을 돌려준다.
    PgTransactionResult processPayment(PurchaseMessage message);

    // 결제 취소(환불·망취소) API. 멱등 키는 orderId — 이미 취소된 거래면 ALREADY_CANCELLED, 실패는 예외.
    PgCancelResult cancelPayment(String orderId);

    // 애매한 응답(PgAmbiguousResponseException) 발생 시, 재시도 전에 실제 처리 여부를 확인하는 조회 API
    PgTransactionResult queryTransaction(String orderId);

}
