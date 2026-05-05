package weverse.serverC.service;

import weverse.serverC.dto.PurchaseMessage;
import java.util.List;

public interface PgClient {

    List<String> processPayments(List<PurchaseMessage> messages);

    // DB 에러 발생 시 호출할 망취소(환불) API 추가
    void cancelPayments(List<String> traceIds);

}