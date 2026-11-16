package promotion.serverA.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record RefundResponse(
        String orderId,
        // 계약 contracts/server-a-openapi.yaml의 RefundResponse.status enum과 일치 (OpenApiContractTest 비교 대상)
        @Schema(allowableValues = {"REFUND_REQUESTED", "REFUNDED"})
        String status
) {}
