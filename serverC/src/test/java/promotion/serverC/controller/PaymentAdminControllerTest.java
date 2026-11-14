package promotion.serverC.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import promotion.serverC.dto.CancelFailureResponse;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.exception.CancelFailureNotFoundException;
import promotion.serverC.exception.InvalidCancelFailureStatusException;
import promotion.serverC.service.PaymentCancelService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentAdminController.class)
class PaymentAdminControllerTest {

    private static final String BASE = "/api/v1/payments/admin/cancel-failures";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentCancelService paymentCancelService;

    @Test
    @DisplayName("관리자는 실패 목록을 조회할 수 있다")
    void listAsAdmin() throws Exception {
        when(paymentCancelService.listFailures("EXHAUSTED")).thenReturn(List.of(response(CancelFailureStatus.EXHAUSTED)));

        mockMvc.perform(get(BASE).param("status", "EXHAUSTED").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("order-1"))
                .andExpect(jsonPath("$[0].status").value("EXHAUSTED"))
                .andExpect(jsonPath("$[0].attempts").value(6));
    }

    @Test
    @DisplayName("관리자가 아니면 목록 조회는 403이고 서비스를 부르지 않는다")
    void listAsUser() throws Exception {
        mockMvc.perform(get(BASE).header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isForbidden());

        verify(paymentCancelService, never()).listFailures(any());
    }

    @Test
    @DisplayName("역할 헤더가 없으면 403이다")
    void listWithoutRole() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("조회할 수 없는 상태를 주면 400이다")
    void listInvalidStatus() throws Exception {
        when(paymentCancelService.listFailures("RESOLVED")).thenThrow(new InvalidCancelFailureStatusException("RESOLVED"));

        mockMvc.perform(get(BASE).param("status", "RESOLVED").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자는 재처리를 요청하고 결과 상태를 받는다")
    void retryAsAdmin() throws Exception {
        when(paymentCancelService.retryManually("order-1")).thenReturn(response(CancelFailureStatus.RESOLVED));

        mockMvc.perform(post(BASE + "/{orderId}/retry", "order-1").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @DisplayName("관리자가 아니면 재처리는 403이고 서비스를 부르지 않는다")
    void retryAsUser() throws Exception {
        mockMvc.perform(post(BASE + "/{orderId}/retry", "order-1").header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isForbidden());

        verify(paymentCancelService, never()).retryManually(any());
    }

    @Test
    @DisplayName("실패 기록이 없는 주문을 재처리하면 404다")
    void retryNotFound() throws Exception {
        when(paymentCancelService.retryManually("none")).thenThrow(new CancelFailureNotFoundException("none"));

        mockMvc.perform(post(BASE + "/{orderId}/retry", "none").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isNotFound());
    }

    private CancelFailureResponse response(CancelFailureStatus status) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 12, 0);
        return new CancelFailureResponse("order-1", status, 6, null, "PG 장애", now, now);
    }
}
