package promotion.serverA.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import promotion.serverA.dto.response.DltResponse;
import promotion.serverA.exception.GlobalExceptionHandler;
import promotion.serverA.service.dlt.DeadLetterService;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController 단위 테스트")
class AdminControllerTest {

    @Mock
    private DeadLetterService deadLetterService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(deadLetterService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/admin/dlt: UNRESOLVED DLT 목록을 반환한다")
    void listUnresolvedDlt_성공() throws Exception {
        given(deadLetterService.listUnresolved()).willReturn(List.of(
                new DltResponse(1L, "order-1", 10L, 2, "재고 부족", "UNRESOLVED"),
                new DltResponse(2L, "UNKNOWN", null, 1, "스키마 오류", "UNRESOLVED")
        ));

        mockMvc.perform(get("/api/v1/admin/dlt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value("order-1"))
                .andExpect(jsonPath("$[1].orderId").value("UNKNOWN"));
    }

    @Test
    @DisplayName("GET /api/v1/admin/dlt: DLT 없으면 빈 배열을 반환한다")
    void listUnresolvedDlt_빈목록() throws Exception {
        given(deadLetterService.listUnresolved()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/dlt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/admin/dlt/{dltId}/retry: DeadLetterService.retryDeadLetter를 호출한다")
    void retryDlt_성공() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dlt/1/retry"))
                .andExpect(status().isOk());

        verify(deadLetterService).retryDeadLetter(1L);
    }
}
