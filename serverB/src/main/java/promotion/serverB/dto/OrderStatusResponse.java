package promotion.serverB.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OrderStatusResponse(
        String orderId,
        // 실행 동작은 String 그대로 두고, OpenAPI 스펙에만 실제로 내려주는 값 목록을 드러낸다
        // (계약 contracts/openapi.yaml의 enum과 일치 — OpenApiContractTest 비교 대상).
        @Schema(description = "Redis에 상태가 없으면 NOT_FOUND",
                allowableValues = {"PENDING", "PAID", "FAILED", "EXPIRED", "NOT_FOUND"})
        String status
) {}
