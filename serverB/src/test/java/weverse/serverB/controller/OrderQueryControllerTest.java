package weverse.serverB.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import weverse.serverB.service.OrderQueryService;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderQueryController.class)
class OrderQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @Test
    @DisplayName("traceId로 주문 상태를 조회하면 200 OK와 함께 상태 정보를 반환한다")
    void getOrderStatus_success() throws Exception {
        // given
        String orderId = "trace-1234";
        String expectedStatus = "PAID";
        given(orderQueryService.getOrderStatus(orderId)).willReturn(expectedStatus);

        // when & then
        mockMvc.perform(get("/api/v1/orders/{orderId}/status", orderId)
                       .accept(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orderId").value(orderId))
               .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    @DisplayName("goodsId로 재고를 조회하면 200 OK와 함께 남은 재고 수량을 반환한다")
    void getStockView_success() throws Exception {
        // given
        Long goodsId = 100L;
        Long remainingStock = 45L;
        given(orderQueryService.getStockView(goodsId)).willReturn(remainingStock);

        // when & then
        mockMvc.perform(get("/api/v1/goods/{goodsId}/stock", goodsId)
                       .accept(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk())
               .andExpect(content().string(String.valueOf(remainingStock)));
    }
}