package com.example.user_service.dto.response;

public record UserCreateResponse(
    Long id,
    String userId,
    String email,
    String username
) {}
