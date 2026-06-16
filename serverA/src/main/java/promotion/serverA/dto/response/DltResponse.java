package promotion.serverA.dto.response;

public record DltResponse(Long id, String orderId, Long goodsId, int quantity, String reason, String status) {}
