package csbot.csbot.client.dto;

// serverA 에러 응답 본문 {status, error, message}. message에 화면 문구가 담긴다 (예: "환불 기간(결제 후 7일)이 지났습니다")
public record ApiErrorResponse(int status, String error, String message) {
}
