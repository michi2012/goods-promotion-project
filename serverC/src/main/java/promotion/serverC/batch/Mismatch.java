package promotion.serverC.batch;

import java.time.LocalDate;

public record Mismatch(LocalDate reconDate, String orderId, String eventType, MismatchType type,
                       Long ourAmount, Long pgAmount) {
}
