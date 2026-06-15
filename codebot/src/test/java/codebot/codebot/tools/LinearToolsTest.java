package codebot.codebot.tools;

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

@DisplayName("LinearTools 단위 테스트")
class LinearToolsTest {

    private MockRestServiceServer server;
    private LinearTools linearTools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.linear.app/graphql");
        server = MockRestServiceServer.bindTo(builder).build();
        linearTools = new LinearTools(builder.build(), "team-uuid-123");
    }

    @Test
    @DisplayName("이슈 생성 성공 시 식별자와 URL을 반환한다")
    void createIssue_성공() {
        // given
        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("viewer")))
                .andRespond(withSuccess("""
                        { "data": { "viewer": { "id": "user-uuid-1" } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueLabels")))
                .andExpect(content().string(containsString("프로모션")))
                .andRespond(withSuccess("""
                        { "data": { "issueLabels": { "nodes": [ { "id": "label-uuid-promotion", "parent": { "name": "도메인" } } ] } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueLabels")))
                .andExpect(content().string(containsString("백엔드")))
                .andRespond(withSuccess("""
                        { "data": { "issueLabels": { "nodes": [ { "id": "label-uuid-backend", "parent": { "name": "직무" } } ] } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueCreate")))
                .andExpect(content().string(containsString("PR 전 체크리스트")))
                .andRespond(withSuccess("""
                        { "data": { "issueCreate": { "success": true, "issue": { "identifier": "MIC-42", "url": "https://linear.app/michi2012/issue/MIC-42" } } } }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearTools.createIssue(
                "프로모션 할인 적용 오류",
                "## 배경\n...\n## 근본 원인\n가설: ...",
                "프로모션", "백엔드");

        // then
        assertThat(result)
                .contains("MIC-42")
                .contains("https://linear.app/michi2012/issue/MIC-42");
    }

    @Test
    @DisplayName("GraphQL 응답에 errors가 있으면 실패 메시지를 반환한다")
    void createIssue_GraphQL오류() {
        // given
        server.expect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "errors": [ { "message": "Authentication required" } ] }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearTools.createIssue("title", "desc", "프로모션", "백엔드");

        // then
        assertThat(result).startsWith("Linear 이슈 생성 실패:");
    }

    @Test
    @DisplayName("HTTP 요청 자체가 실패하면 실패 메시지를 반환한다")
    void createIssue_HTTP실패() {
        // given
        server.expect(method(HttpMethod.POST))
                .andRespond(withServerError());

        // when
        String result = linearTools.createIssue("title", "desc", "프로모션", "백엔드");

        // then
        assertThat(result).startsWith("Linear 이슈 생성 실패:");
    }

    @Test
    @DisplayName("issueCreate 응답의 success가 false이면 실패 메시지를 반환한다")
    void createIssue_success_false() {
        // given
        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("viewer")))
                .andRespond(withSuccess("""
                        { "data": { "viewer": { "id": "user-uuid-1" } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueLabels")))
                .andRespond(withSuccess("""
                        { "data": { "issueLabels": { "nodes": [ { "id": "label-uuid-promotion", "parent": { "name": "도메인" } } ] } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueLabels")))
                .andRespond(withSuccess("""
                        { "data": { "issueLabels": { "nodes": [ { "id": "label-uuid-backend", "parent": { "name": "직무" } } ] } } }
                        """, MediaType.APPLICATION_JSON));

        server.expect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("issueCreate")))
                .andRespond(withSuccess("""
                        { "data": { "issueCreate": { "success": false, "issue": null } } }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = linearTools.createIssue("title", "desc", "프로모션", "백엔드");

        // then
        assertThat(result).startsWith("Linear 이슈 생성 실패:");
    }
}
