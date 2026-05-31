package weverse.serverC.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import weverse.serverC.dto.PaymentResponse;
import weverse.serverC.exception.PaymentNotFoundException;
import weverse.serverC.service.PaymentService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("존재하는 orderId로 조회하면 200과 결제 정보를 반환한다")
    void getByOrderId_Success() throws Exception {
        // given
        String orderId = "trace-1234";
        PaymentResponse response = new PaymentResponse(orderId, 1L, 4L, 1, "CARD", "PAID", LocalDateTime.now());
        when(paymentService.getByOrderId(orderId)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("존재하지 않는 orderId로 조회하면 404를 반환한다")
    void getByOrderId_NotFound() throws Exception {
        // given
        String orderId = "not-exist";
        when(paymentService.getByOrderId(orderId)).thenThrow(new PaymentNotFoundException(orderId));

        // when & then
        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("userId로 결제 내역을 조회하면 200과 목록을 반환한다")
    void getByUserId_Success() throws Exception {
        // given
        Long userId = 1L;
        List<PaymentResponse> responses = List.of(
                new PaymentResponse("order-1", userId, 4L, 1, "CARD", "PAID", LocalDateTime.now()),
                new PaymentResponse("order-2", userId, 5L, 2, "CARD", "PAID", LocalDateTime.now())
        );
        when(paymentService.getByUserId(userId, 0, 20)).thenReturn(responses);

        // when & then
        mockMvc.perform(get("/api/v1/payments/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value("order-1"));
    }

    @Test
    @DisplayName("page, size 파라미터를 지정하면 해당 값으로 조회한다")
    void getByUserId_WithCustomPageSize() throws Exception {
        // given
        Long userId = 1L;
        when(paymentService.getByUserId(userId, 1, 10)).thenReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/v1/payments/users/{userId}", userId)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());
    }
}
