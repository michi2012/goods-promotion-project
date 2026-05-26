package weverse.serverA.dto;

public record SagaStateData(
        Long orderEntityId,
        Long userId,
        Long goodsId,
        int quantity
) {}
