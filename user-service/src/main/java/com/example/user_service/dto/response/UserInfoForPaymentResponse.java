package com.example.user_service.dto.response;

public record UserInfoForPaymentResponse(
        String userId,
        String userName,
        String userEmail,
        String paymentMethodId,
        String billingKey
) {}
