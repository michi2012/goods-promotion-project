package com.example.user_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

@Builder
public record PaymentMethodRegisterRequest(
        @NotBlank(message = "카드 번호는 필수 입력 값입니다.")
        String cardNumber,

        @NotBlank(message = "만료일은 필수 입력 값입니다.")
        @Pattern(regexp = "^(0[1-9]|1[0-2])\\/([0-9]{2})$", message = "만료일 형식이 올바르지 않습니다. (예: MM/YY)")
        String expiryDate,

        @NotBlank(message = "CVC는 필수 입력 값입니다.")
        @Pattern(regexp = "^[0-9]{3,4}$", message = "CVC 형식이 올바르지 않습니다.")
        String cvc
) {
}
