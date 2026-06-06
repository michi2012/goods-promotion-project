package com.example.user_service.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UserUpdateRequest(
        @NotBlank(message = "이름은 필수 입력 값입니다.")
        String username,

        @NotBlank(message = "전화번호는 필수 입력 값입니다.")
        String phoneNumber
) {
}
