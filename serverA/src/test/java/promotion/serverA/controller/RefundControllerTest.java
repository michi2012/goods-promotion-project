package promotion.serverA.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import promotion.serverA.dto.response.RefundResponse;
import promotion.serverA.exception.GlobalExceptionHandler;
import promotion.serverA.exception.RefundException;
import promotion.serverA.service.RefundService;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefundController 단위 테스트")
class RefundControllerTest {

    @Mock
    private RefundService refundService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RefundController(refundService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/orders/{orderId}/refund: X-User-Id를 로그인 ID로 넘기고 접수 결과를 200으로 돌려준다")
    void requestRefund_success() throws Exception {
        given(refundService.requestRefund("order-1", "login-abc"))
                .willReturn(new RefundResponse("order-1", "REFUND_REQUESTED"));

        mockMvc.perform(post("/api/v1/orders/order-1/refund").header("X-User-Id", "login-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"));
    }

    @Test
    @DisplayName("X-User-Id가 없으면 서비스 판단대로 401 UNAUTHORIZED (필수 헤더 누락 500으로 새지 않는다)")
    void requestRefund_missingHeader() throws Exception {
        given(refundService.requestRefund("order-1", null)).willThrow(RefundException.missingUser());

        mockMvc.perform(post("/api/v1/orders/order-1/refund"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(refundService).requestRefund("order-1", null);
    }

    @Test
    @DisplayName("환불 거절은 명세의 상태·코드·문구로 응답한다 (404·409·503)")
    void requestRefund_rejections() throws Exception {
        given(refundService.requestRefund("missing", "u")).willThrow(RefundException.orderNotFound());
        given(refundService.requestRefund("failed", "u")).willThrow(RefundException.notAllowedStatus());
        given(refundService.requestRefund("old", "u")).willThrow(RefundException.periodExpired(7));
        given(refundService.requestRefund("down", "u")).willThrow(RefundException.userLookupUnavailable());

        mockMvc.perform(post("/api/v1/orders/missing/refund").header("X-User-Id", "u"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다"));
        mockMvc.perform(post("/api/v1/orders/failed/refund").header("X-User-Id", "u"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REFUND_NOT_ALLOWED_STATUS"))
                .andExpect(jsonPath("$.message").value("환불할 수 없는 주문 상태입니다"));
        mockMvc.perform(post("/api/v1/orders/old/refund").header("X-User-Id", "u"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REFUND_PERIOD_EXPIRED"))
                .andExpect(jsonPath("$.message").value("환불 기간(결제 후 7일)이 지났습니다"));
        mockMvc.perform(post("/api/v1/orders/down/refund").header("X-User-Id", "u"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("USER_LOOKUP_UNAVAILABLE"));
    }
}
