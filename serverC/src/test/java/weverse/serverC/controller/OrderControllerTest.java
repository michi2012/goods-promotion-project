package weverse.serverC.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.dto.ServerCResponse;
import weverse.serverC.service.OrderProcessingService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private OrderProcessingService orderProcessingService;

    @Test
    @DisplayName("벌크 주문 접수 성공 API 테스트")
    void receiveBulkOrders_Success() throws Exception {
        // Given
        PurchaseMessage msg = new PurchaseMessage("trace-1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        List<PurchaseMessage> payloads = List.of(msg);

        ServerCResponse mockResponse = new ServerCResponse(true, "Processed", List.of());
        given(orderProcessingService.processBulkOrders(anyList())).willReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/c/orders/bulk")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(payloads)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value(true))
               .andExpect(jsonPath("$.message").value("Processed"))
               .andExpect(jsonPath("$.failedTraceIds").isEmpty());
    }
}