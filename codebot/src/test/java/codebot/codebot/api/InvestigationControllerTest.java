package codebot.codebot.api;

import codebot.codebot.agent.CodebotAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvestigationController.class)
@DisplayName("InvestigationController 단위 테스트")
class InvestigationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CodebotAgentService agentService;

    @Test
    @DisplayName("정상 요청 시 조사 결과를 반환한다")
    void investigate_성공() throws Exception {
        // given
        InvestigationRequest request = new InvestigationRequest("thread-123", "PaymentService에서 NPE가 발생해요");
        given(agentService.investigate("thread-123", "PaymentService에서 NPE가 발생해요"))
                .willReturn("조사 결과: ...");

        // when & then
        mockMvc.perform(post("/internal/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("조사 결과: ..."));
    }

    @Test
    @DisplayName("conversationId가 비어 있으면 400을 반환한다")
    void investigate_검증실패() throws Exception {
        // given
        InvestigationRequest request = new InvestigationRequest("", "PaymentService에서 NPE가 발생해요");

        // when & then
        mockMvc.perform(post("/internal/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
