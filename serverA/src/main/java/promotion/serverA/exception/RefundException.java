package promotion.serverA.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// 환불 요청 거절. 명세(docs/specs/user-refund.md "API")의 HTTP 상태·에러 코드·화면 문구를 그대로 담는다.
// BusinessException 핸들러는 항상 400이라 상태를 따로 갖고, GlobalExceptionHandler가 이 타입을 먼저 처리한다.
@Getter
public class RefundException extends BusinessException {

    private final HttpStatus status;

    private RefundException(HttpStatus status, String errorCode, String message) {
        super(message, errorCode);
        this.status = status;
    }

    // X-User-Id 없음 (운영에서는 게이트웨이가 JWT를 검증해 넣는다)
    public static RefundException missingUser() {
        return new RefundException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
    }

    // 주문이 없거나 본인 주문이 아님 — 둘을 구분하지 않는다 (P-2, S-5)
    public static RefundException orderNotFound() {
        return new RefundException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다");
    }

    public static RefundException notAllowedStatus() {
        return new RefundException(HttpStatus.CONFLICT, "REFUND_NOT_ALLOWED_STATUS", "환불할 수 없는 주문 상태입니다");
    }

    public static RefundException periodExpired(long periodDays) {
        return new RefundException(HttpStatus.CONFLICT, "REFUND_PERIOD_EXPIRED",
                "환불 기간(결제 후 " + periodDays + "일)이 지났습니다");
    }

    public static RefundException userLookupUnavailable() {
        return new RefundException(HttpStatus.SERVICE_UNAVAILABLE, "USER_LOOKUP_UNAVAILABLE", "잠시 후 다시 시도해주세요.");
    }
}
