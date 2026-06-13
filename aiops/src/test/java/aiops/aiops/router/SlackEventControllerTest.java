package aiops.aiops.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlackEventController.class)
@DisplayName("SlackEventController 단위 테스트")
class SlackEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RouterService routerService;

    @Test
    @DisplayName("url_verification 요청에는 challenge 값을 그대로 반환한다")
    void handleEvent_urlVerification() throws Exception {
        // given
        Map<String, Object> payload = Map.of(
                "type", "url_verification",
                "challenge", "challenge-token-123"
        );

        // when & then
        mockMvc.perform(post("/slack/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("challenge-token-123"));
    }

    @Test
    @DisplayName("app_mention 이벤트는 라우터로 위임한다")
    void handleEvent_appMention은라우터로위임한다() throws Exception {
        // given
        Map<String, Object> payload = Map.of(
                "type", "event_callback",
                "event", Map.of(
                        "type", "app_mention",
                        "channel", "C123",
                        "ts", "1111.0001",
                        "thread_ts", "1000.0000",
                        "text", "<@BOT> 파드 상태 확인해줘"
                )
        );

        // when
        mockMvc.perform(post("/slack/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // then (비동기 처리 대기)
        then(routerService).should(timeout(2000))
                .handleAppMention("C123", "1000.0000", "<@BOT> 파드 상태 확인해줘");
    }

    @Test
    @DisplayName("thread_ts가 없으면 ts를 conversationId로 사용한다")
    void handleEvent_threadTs없으면ts를사용한다() throws Exception {
        // given
        Map<String, Object> payload = Map.of(
                "type", "event_callback",
                "event", Map.of(
                        "type", "app_mention",
                        "channel", "C123",
                        "ts", "2222.0002",
                        "text", "<@BOT> 안녕"
                )
        );

        // when
        mockMvc.perform(post("/slack/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // then
        then(routerService).should(timeout(2000))
                .handleAppMention("C123", "2222.0002", "<@BOT> 안녕");
    }

    @Test
    @DisplayName("app_mention이 아닌 이벤트는 무시한다")
    void handleEvent_appMention이아닌이벤트는무시한다() throws Exception {
        // given
        Map<String, Object> payload = Map.of(
                "type", "event_callback",
                "event", Map.of(
                        "type", "message",
                        "channel", "C123",
                        "ts", "3333.0003",
                        "text", "일반 메시지"
                )
        );

        // when & then
        mockMvc.perform(post("/slack/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        then(routerService).should(never()).handleAppMention(anyString(), anyString(), anyString());
    }
}
