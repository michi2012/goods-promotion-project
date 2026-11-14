package promotion.serverC.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import promotion.serverC.dto.CancelFailureResponse;
import promotion.serverC.exception.AdminForbiddenException;
import promotion.serverC.service.PaymentCancelService;

import java.util.List;

/**
 * PG 취소 실패 관리자 API. X-User-Role은 게이트웨이가 JWT를 검증해 넣는다
 * (외부 노출은 게이트웨이뿐이라는 전제 — docs/specs/user-refund.md "API").
 */
@RestController
@RequestMapping("/api/v1/payments/admin/cancel-failures")
@RequiredArgsConstructor
public class PaymentAdminController {

    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final PaymentCancelService paymentCancelService;

    @GetMapping
    public ResponseEntity<List<CancelFailureResponse>> list(
            @RequestHeader(value = USER_ROLE_HEADER, required = false) String role,
            @RequestParam(required = false) String status) {
        requireAdmin(role);
        return ResponseEntity.ok(paymentCancelService.listFailures(status));
    }

    @PostMapping("/{orderId}/retry")
    public ResponseEntity<CancelFailureResponse> retry(
            @RequestHeader(value = USER_ROLE_HEADER, required = false) String role,
            @PathVariable String orderId) {
        requireAdmin(role);
        return ResponseEntity.ok(paymentCancelService.retryManually(orderId));
    }

    private void requireAdmin(String role) {
        if (!ADMIN_ROLE.equals(role)) {
            throw new AdminForbiddenException();
        }
    }
}
