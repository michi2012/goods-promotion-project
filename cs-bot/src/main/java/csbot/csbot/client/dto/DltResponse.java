package csbot.csbot.client.dto;

public record DltResponse(Long id, String orderId, Long goodsId, int quantity, String reason, String status) {}
