package promotion.serverA.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import promotion.serverA.dto.request.CreateGoodsRequest;
import promotion.serverA.dto.response.CreateGoodsResponse;
import promotion.serverA.dto.response.GoodsResponse;
import promotion.serverA.exception.GlobalExceptionHandler;
import promotion.serverA.exception.GoodsNotFoundException;
import promotion.serverA.service.GoodsService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        given(goodsService.findById(1L)).willReturn(new GoodsResponse(1L, "테스트 상품", 15_000L, 100));

        mockMvc.perform(get("/api/v1/goods/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("테스트 상품"))
                .andExpect(jsonPath("$.price").value(15000))
                .andExpect(jsonPath("$.stock").value(100));
    }

    @Test
    @DisplayName("POST /api/v1/goods: 가격을 포함해 상품을 생성한다")
    void createGoods_성공() throws Exception {
        given(goodsService.createGoods(new CreateGoodsRequest("신규 상품", 15_000L, 10)))
                .willReturn(new CreateGoodsResponse(1L, "신규 상품", 15_000L, 10));

        mockMvc.perform(post("/api/v1/goods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규 상품\",\"price\":15000,\"stock\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.price").value(15000));
    }

    @Test
    @DisplayName("POST /api/v1/goods: 가격이 없으면 400을 반환한다")
    void createGoods_가격누락() throws Exception {
        mockMvc.perform(post("/api/v1/goods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규 상품\",\"stock\":10}"))
                .andExpect(status().isBadRequest());

        verify(goodsService, never()).createGoods(any());
    }

    @Test
    @DisplayName("POST /api/v1/goods: 가격이 0원이면 400을 반환한다")
    void createGoods_0원() throws Exception {
        mockMvc.perform(post("/api/v1/goods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"신규 상품\",\"price\":0,\"stock\":10}"))
                .andExpect(status().isBadRequest());

        verify(goodsService, never()).createGoods(any());
    }

    @Test
    @DisplayName("GET /api/v1/goods/{goodsId}: 존재하지 않는 상품이면 400을 반환한다")
    void getGoods_존재하지않는상품() throws Exception {
        given(goodsService.findById(999L)).willThrow(new GoodsNotFoundException());

        mockMvc.perform(get("/api/v1/goods/999"))
                .andExpect(status().isBadRequest());
    }
}
