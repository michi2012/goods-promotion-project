package aiops.aiops.linear;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("LinearAuditService 단위 테스트")
class LinearAuditServiceTest {

    private MockRestServiceServer server;
    private LinearAuditService linearAuditService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.linear.app/graphql");
        server = MockRestServiceServer.bindTo(builder).build();
        linearAuditService = new LinearAuditService(builder.build(), "team-uuid-123");
    }

    @Test
    @DisplayName("감사 티켓 생성 성공 시 식별자와 URL을 반환한다")
    void createAuditTicket_성공() {
        // given
        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueCreate")))
                .andExpect(content().string(containsString("ROLLOUT_RESTART")))
                .andRespond(withSuccess("""
                        { "data": { "issueCreate": { "success": true, "issue": { "identifier": "MIC-50", "url": "https://linear.app/michi2012/issue/MIC-50" } } } }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearAuditService.createAuditTicket(
                "ROLLOUT_RESTART",
                "{\"deployment\":\"server-a\",\"namespace\":\"promotion\"}",
                "데드락 의심",
                "✅ server-a 롤링 재시작 완료");

        // then
        assertThat(result)
                .contains("MIC-50")
                .contains("https://linear.app/michi2012/issue/MIC-50");
    }

    @Test
    @DisplayName("GraphQL 응답에 errors가 있으면 실패 메시지를 반환한다")
    void createAuditTicket_GraphQL오류() {
        // given
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "errors": [ { "message": "Authentication required" } ] }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearAuditService.createAuditTicket("ROLLOUT_RESTART", "{}", "사유", "결과");

        // then
        assertThat(result).startsWith("Linear 감사 티켓 생성 실패:");
    }

    @Test
    @DisplayName("HTTP 요청 자체가 실패하면 실패 메시지를 반환한다")
    void createAuditTicket_HTTP실패() {
        // given
        server.expect(method(HttpMethod.POST))
                .andRespond(withServerError());

        // when
        String result = linearAuditService.createAuditTicket("ROLLOUT_RESTART", "{}", "사유", "결과");

        // then
        assertThat(result).startsWith("Linear 감사 티켓 생성 실패:");
    }

    @Test
    @DisplayName("issueCreate 응답의 success가 false이면 실패 메시지를 반환한다")
    void createAuditTicket_success_false() {
        // given
        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueCreate")))
                .andRespond(withSuccess("""
                        { "data": { "issueCreate": { "success": false, "issue": null } } }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearAuditService.createAuditTicket("ROLLOUT_RESTART", "{}", "사유", "결과");

        // then
        assertThat(result).startsWith("Linear 감사 티켓 생성 실패:");
    }
}
