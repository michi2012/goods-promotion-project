package weverse.serverC.service;

import weverse.serverC.dto.PurchaseMessage;
import java.util.List;

public interface PgClient {

    boolean processPayment(PurchaseMessage message);

    // DB 에러 발생 시 호출할 망취소(환불) API
    void cancelPayments(List<String> orderIds);

}