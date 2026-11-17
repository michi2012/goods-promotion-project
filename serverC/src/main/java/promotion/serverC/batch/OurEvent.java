package promotion.serverC.batch;

import java.time.LocalDateTime;

/**
 * 우리 결제 이벤트 1건 (payments의 approved_at 또는 cancelled_at). amount는 결제 금액(옛 결제는 null).
 */
public record OurEvent(String orderId, String eventType, Long amount, LocalDateTime eventAt) {
}
