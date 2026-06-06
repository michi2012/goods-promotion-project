package com.example.user_service.dto.response;

public record UserUpdateResponse(
        Long id,
        String userId,
        String username,
        String email,
        String phoneNumber
) {
}
