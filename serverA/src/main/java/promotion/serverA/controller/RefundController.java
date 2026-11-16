package promotion.serverA.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import promotion.serverA.dto.response.RefundResponse;
import promotion.serverA.service.RefundService;

// 주문 환불 요청 (docs/specs/user-refund.md "API"). 게이트웨이 serverA-refund 라우트가 이 경로로 보낸다.
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    // X-User-Id가 없으면 401 — 필수 헤더 누락 예외(500)로 새지 않게 서비스에서 판단한다
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<RefundResponse> requestRefund(
            @PathVariable String orderId,
            @RequestHeader(value = "X-User-Id", required = false) String loginId) {
        return ResponseEntity.ok(refundService.requestRefund(orderId, loginId));
    }
}
