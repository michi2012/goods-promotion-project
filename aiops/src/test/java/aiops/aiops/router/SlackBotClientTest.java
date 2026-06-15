package aiops.aiops.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@DisplayName("SlackBotClient 단위 테스트")
class SlackBotClientTest {

    private MockRestServiceServer server;
    private SlackBotClient slackBotClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://slack.com/api");
        server = MockRestServiceServer.bindTo(builder).build();
        slackBotClient = new SlackBotClient(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("정상 응답이면 메시지를 전송한다")
    void postMessage_성공() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("thread-123")))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "안녕하세요");
    }

    @Test
    @DisplayName("ok가 false인 응답이어도 예외를 던지지 않는다")
    void postMessage_slack오류응답() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "channel_not_found" }
                        """, MediaType.APPLICATION_JSON));

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "안녕하세요");
    }

    @Test
    @DisplayName("HTTP 요청이 실패해도 예외를 던지지 않는다")
    void postMessage_HTTP실패() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andRespond(withServerError());

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "안녕하세요");
    }

    @Test
    @DisplayName("**볼드** 마크다운을 Slack *볼드* 문법으로 변환한다")
    void postMessage_볼드변환() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andExpect(content().string(containsString("*중요*")))
                .andExpect(content().string(not(containsString("**중요**"))))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "**중요** 합니다");
    }

    @Test
    @DisplayName("### 헤딩 마크다운을 Slack *볼드* 문법으로 변환한다")
    void postMessage_헤딩변환() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andExpect(content().string(containsString("*제목*")))
                .andExpect(content().string(not(containsString("### 제목"))))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "### 제목\n내용");
    }

    @Test
    @DisplayName("[text](url) 마크다운을 Slack <url|text> 링크 문법으로 변환한다")
    void postMessage_링크변환() {
        // given
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andExpect(content().string(containsString("<https://example.com|문서>")))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        // when & then (예외 없이 종료)
        slackBotClient.postMessage("C123", "thread-123", "[문서](https://example.com) 참고");
    }
}
