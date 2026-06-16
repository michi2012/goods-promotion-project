package com.example.user_service.dto.response;
import com.example.user_service.entity.User;

public record InternalUserResponse(
        Long id,
        String userId,
        String username
) {
    public static InternalUserResponse fromEntity(User user) {
        return new InternalUserResponse(user.getId(), user.getUserId(), user.getUsername());
    }
}
