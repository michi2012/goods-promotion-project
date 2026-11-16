package promotion.serverA.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// payment-cancelled (serverC → serverA): PG 취소 확정. 사용자 환불·망취소 모두 발행되며, 환불인지는 주문 상태로 판단한다.
// serverA는 orderId만 쓴다 (cancelledAt 등 나머지 필드는 무시 — 필드 추가에도 깨지지 않게).
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCancelledMessage(String orderId) {
}
