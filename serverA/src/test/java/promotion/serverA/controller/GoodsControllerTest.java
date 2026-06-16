package promotion.serverA.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import promotion.serverA.dto.response.GoodsResponse;
import promotion.serverA.exception.GlobalExceptionHandler;
import promotion.serverA.exception.GoodsNotFoundException;
import promotion.serverA.service.GoodsService;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoodsController 단위 테스트")
class GoodsControllerTest {

    @Mock
    private GoodsService goodsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GoodsController(goodsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/goods/{goodsId}: 상품 정보를 반환한다")
    void getGoods_성공() throws Exception {
        given(goodsService.findById(1L)).willReturn(new GoodsResponse(1L, "테스트 상품", 100));

        mockMvc.perform(get("/api/v1/goods/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("테스트 상품"))
                .andExpect(jsonPath("$.stock").value(100));
    }

    @Test
    @DisplayName("GET /api/v1/goods/{goodsId}: 존재하지 않는 상품이면 400을 반환한다")
    void getGoods_존재하지않는상품() throws Exception {
        given(goodsService.findById(999L)).willThrow(new GoodsNotFoundException());

        mockMvc.perform(get("/api/v1/goods/999"))
                .andExpect(status().isBadRequest());
    }
}
