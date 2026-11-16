package csbot.csbot.client.dto;

// serverA POST /api/v1/orders/{orderId}/refund 성공 응답 (status: REFUND_REQUESTED | REFUNDED)
public record RefundResponse(String orderId, String status) {
}
