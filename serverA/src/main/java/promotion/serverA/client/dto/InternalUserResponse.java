package promotion.serverA.client.dto;

// user-service GET /internal/api/users/{loginId} 응답 중 serverA가 쓰는 필드만 (숫자 사용자 ID)
public record InternalUserResponse(
        Long id
) {}
