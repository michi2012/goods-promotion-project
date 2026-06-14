package codebot.codebot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("CodeSearchTools 단위 테스트")
class CodeSearchToolsTest {

    private MockRestServiceServer server;
    private CodeSearchTools codeSearchTools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient githubClient = builder.build();
        codeSearchTools = new CodeSearchTools(githubClient, "michi2012", "goods-promotion-project");
    }

    @Test
    @DisplayName("코드 검색 성공 시 파일 경로와 URL 목록을 반환한다")
    void searchCode_성공() {
        // given
        String responseJson = """
                {
                  "total_count": 1,
                  "items": [
                    {
                      "path": "aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java",
                      "html_url": "https://github.com/michi2012/goods-promotion-project/blob/main/AiOpsAgentService.java"
                    }
                  ]
                }
                """;
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/search/code");
                    assertThat(request.getURI().getRawQuery())
                            .contains("AiOpsAgentService")
                            .contains("michi2012/goods-promotion-project")
                            .contains("per_page=10");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = codeSearchTools.searchCode("AiOpsAgentService");

        // then
        assertThat(result)
                .contains("aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java")
                .contains("https://github.com/michi2012/goods-promotion-project/blob/main/AiOpsAgentService.java");
    }

    @Test
    @DisplayName("검색 결과가 없으면 결과 없음 메시지를 반환한다")
    void searchCode_결과없음() {
        // given
        String responseJson = """
                { "total_count": 0, "items": [] }
                """;
        server.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = codeSearchTools.searchCode("존재하지않는키워드");

        // then
        assertThat(result).isEqualTo("검색 결과 없음");
    }

    @Test
    @DisplayName("GitHub API 호출이 실패하면 실패 메시지를 반환한다")
    void searchCode_API실패() {
        // given
        server.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = codeSearchTools.searchCode("AiOpsAgentService");

        // then
        assertThat(result).startsWith("GitHub 코드 검색 실패:");
    }

    @Test
    @DisplayName("파일 내용 조회 성공 시 raw 형식 헤더로 요청하고 내용을 반환한다")
    void getFileContent_성공() {
        // given
        String fileContent = "package aiops.aiops.agent;\n\npublic class AiOpsAgentService {}\n";
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/michi2012/goods-promotion-project/contents/README.md"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess(fileContent, MediaType.TEXT_PLAIN));

        // when
        String result = codeSearchTools.getFileContent("README.md");

        // then
        assertThat(result).isEqualTo(fileContent);
    }

    @Test
    @DisplayName("파일 조회가 실패하면 실패 메시지를 반환한다")
    void getFileContent_API실패() {
        // given
        server.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = codeSearchTools.getFileContent("NotFound.java");

        // then
        assertThat(result).startsWith("GitHub 파일 조회 실패:");
    }
}
