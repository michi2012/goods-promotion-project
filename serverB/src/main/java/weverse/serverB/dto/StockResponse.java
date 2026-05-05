package weverse.serverB.dto;

// 1. 재고 조회 응답용 DTO
public record StockResponse(
        Long goodsId,
        int remainingStock,
        boolean isSoldOut
) {}
