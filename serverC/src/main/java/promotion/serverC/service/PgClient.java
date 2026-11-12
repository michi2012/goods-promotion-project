package promotion.serverC.service;

import promotion.serverC.dto.PurchaseMessage;
import java.util.List;

public interface PgClient {

    // 승인 요청. 승인 여부와 함께 PG가 실제 승인한 금액을 돌려준다.
    PgTransactionResult processPayment(PurchaseMessage message);

    // DB 에러 발생 시 호출할 망취소(환불) API
    void cancelPayments(List<String> orderIds);

    // 애매한 응답(PgAmbiguousResponseException) 발생 시, 재시도 전에 실제 처리 여부를 확인하는 조회 API
    PgTransactionResult queryTransaction(String orderId);

}
