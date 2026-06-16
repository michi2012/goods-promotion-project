package csbot.csbot.controller;

import csbot.csbot.context.CsUserContext;
import csbot.csbot.router.CsChatAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CsChatController.class)
@DisplayName("CsChatController 단위 테스트")
class CsChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CsChatAgentService agentService;

    @MockitoBean
    private CsUserContext csUserContext;

    @Test
    @DisplayName("POST /api/v1/cs-chat/messages: 정상 요청 시 에이전트 응답을 반환한다")
    void sendMessage_성공() throws Exception {
        given(agentService.chat("conv-1", "주문 내역 알려줘")).willReturn("확인해드리겠습니다.");

        mockMvc.perform(post("/api/v1/cs-chat/messages")
                        .header("X-User-Id", "user-uuid-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"주문 내역 알려줘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("확인해드리겠습니다."));

        verify(csUserContext).setLoginId("user-uuid-123");
    }

    @Test
    @DisplayName("POST /api/v1/cs-chat/messages: X-User-Id 헤더가 없으면 400을 반환한다")
    void sendMessage_헤더없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/cs-chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"주문 내역 알려줘\"}"))
                .andExpect(status().isBadRequest());
    }
}
