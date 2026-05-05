package weverse.serverB.dto;

public record CompensationRequest(
        String traceId,
        Long goodsId,
        int quantity,
        String reason
) {}