package promotion.serverC.batch;

import java.time.LocalDateTime;

/**
 * PG 거래 파일 한 줄 = 거래 이벤트 1건. eventType은 APPROVE / CANCEL ({@link promotion.serverC.service.MockPgLedger}).
 */
public record PgLine(String orderId, String eventType, Long amount, LocalDateTime occurredAt) {
}
