package weverse.serverA.dto;

public record SagaStateData(
        Long orderId,
        Long userId,
        Long goodsId,
        int quantity
) {}
