package com.example.user_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserPasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호는 필수 입력 값입니다.")
        String oldPassword,

        @NotBlank(message = "새 비밀번호는 필수 입력 값입니다.")
        String newPassword
) {
}