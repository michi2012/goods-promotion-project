package com.example.user_service.dto.response;

import lombok.Builder;

@Builder
public record PaymentMethodResponse(
        Long id,
        String cardIssuer,
        String cardNumberMasked,
        boolean isDefault
) {
}
