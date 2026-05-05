package weverse.serverA.dto.request;

public record CompensationRequest(
        String traceId,
        Long goodsId,
        int quantity, // 💡 롤백해야 할 정확한 수량
        String reason
) {}