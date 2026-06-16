package csbot.csbot.client.dto;

public record UserProfileResponse(
        Long id,
        String userId,
        String email,
        String username,
        String phoneNumber
) {
}
