package codebot.codebot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("PullRequestTools 단위 테스트")
class PullRequestToolsTest {

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final String BRANCH = "feature/mic-12-codebot-fix";
    private static final String FILE_PATH = "aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java";
    private static final String NEW_CONTENT = "fixed file content";

    @TempDir
    Path repoDir;

    private MockRestServiceServer server;
    private PullRequestTools pullRequestTools;

    @BeforeEach
    void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();

        run("git", "init", "-q");
        Files.createDirectories(repoDir.resolve(FILE_PATH).getParent());
        Files.writeString(repoDir.resolve(FILE_PATH), "package aiops.aiops.agent;\n\npublic class AiOpsAgentService {\n}\n");
        run("git", "add", "-A");
        run("git", "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "-m", "init");

        pullRequestTools = new PullRequestTools(builder.build(), OWNER, REPO, repoDir.toString());
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(repoDir.toFile()).start();
        process.waitFor();
    }

    @Test
    @DisplayName("브랜치가 없으면 새 브랜치를 만들고 커밋 후 새 PR을 생성한다")
    void createFixPullRequest_신규브랜치_PR생성() {
        // 1. 브랜치 존재 확인 -> 404
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // 2. main 브랜치 최신 SHA 조회
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/main"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "object": { "sha": "main-sha-123" } }
                        """, MediaType.APPLICATION_JSON));

        // 3. 새 브랜치 생성
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("refs/heads/" + BRANCH)))
                .andExpect(content().string(containsString("main-sha-123")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 4. 파일 SHA 조회
        server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH);
                    assertThat(request.getURI().getQuery()).contains("ref=" + BRANCH);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "sha": "file-sha-456" }
                        """, MediaType.APPLICATION_JSON));

        // 5. 파일 커밋
        String expectedBase64 = Base64.getEncoder().encodeToString(NEW_CONTENT.getBytes(StandardCharsets.UTF_8));
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString(expectedBase64)))
                .andExpect(content().string(containsString("file-sha-456")))
                .andExpect(content().string(containsString(BRANCH)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 6. PR 생성
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Closes MIC-12")))
                .andRespond(withSuccess("""
                        { "html_url": "https://github.com/test-owner/test-repo/pull/10" }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = pullRequestTools.createFixPullRequest(
                FILE_PATH, NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result)
                .contains("새 PR을 생성했습니다")
                .contains("https://github.com/test-owner/test-repo/pull/10");
    }

    @Test
    @DisplayName("브랜치가 이미 있고 열린 PR이 있으면 추가 커밋 후 기존 PR을 반환한다")
    void createFixPullRequest_기존브랜치_기존PR있음() {
        // 1. 브랜치 존재 확인 -> 200
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "object": { "sha": "branch-sha-789" } }
                        """, MediaType.APPLICATION_JSON));

        // 2. 파일 SHA 조회
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "sha": "file-sha-789" }
                        """, MediaType.APPLICATION_JSON));

        // 3. 파일 커밋 (추가 커밋)
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 4. 열린 PR 조회 -> 존재
        server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/repos/" + OWNER + "/" + REPO + "/pulls");
                    assertThat(request.getURI().getQuery())
                            .contains("head=" + OWNER + ":" + BRANCH)
                            .contains("state=open");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [ { "html_url": "https://github.com/test-owner/test-repo/pull/7" } ]
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = pullRequestTools.createFixPullRequest(
                FILE_PATH, NEW_CONTENT, "MIC-12", "fix: 추가 수정", "fix: 추가 수정", "## 변경 요약\n추가 수정했습니다.");

        // then
        assertThat(result)
                .contains("기존 브랜치(" + BRANCH + ")")
                .contains("https://github.com/test-owner/test-repo/pull/7");
    }

    @Test
    @DisplayName("브랜치가 이미 있지만 열린 PR이 없으면 안내 메시지를 반환한다")
    void createFixPullRequest_기존브랜치_PR없음() {
        // 1. 브랜치 존재 확인 -> 200
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "object": { "sha": "branch-sha-789" } }
                        """, MediaType.APPLICATION_JSON));

        // 2. 파일 SHA 조회
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "sha": "file-sha-789" }
                        """, MediaType.APPLICATION_JSON));

        // 3. 파일 커밋
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 4. 열린 PR 조회 -> 없음
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/pulls"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        // when
        String result = pullRequestTools.createFixPullRequest(
                FILE_PATH, NEW_CONTENT, "MIC-12", "fix: 추가 수정", "fix: 추가 수정", "## 변경 요약\n추가 수정했습니다.");

        // then
        assertThat(result).startsWith("브랜치 " + BRANCH + "에 커밋을 반영했지만 열린 PR을 찾지 못했습니다");
    }

    @Test
    @DisplayName("GitHub API 호출이 실패하면 실패 메시지를 반환한다")
    void createFixPullRequest_API실패() {
        // given
        server.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = pullRequestTools.createFixPullRequest(
                FILE_PATH, NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result).startsWith("PR 생성/커밋 실패:");
    }

    @Test
    @DisplayName(".github/ 경로는 GitHub API 호출 없이 안내 메시지를 반환한다")
    void createFixPullRequest_보호경로_github() {
        // when
        String result = pullRequestTools.createFixPullRequest(
                ".github/workflows/ci.yml", NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result).contains("보안상").contains(".github/workflows/ci.yml");
        server.verify();
    }

    @Test
    @DisplayName(".env 경로는 GitHub API 호출 없이 안내 메시지를 반환한다")
    void createFixPullRequest_보호경로_env() {
        // when
        String result = pullRequestTools.createFixPullRequest(
                ".env", NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result).contains("보안상").contains(".env");
        server.verify();
    }

    @Test
    @DisplayName("filePath가 파일명만 주어지면 git ls-files로 실제 경로를 찾아 자동 보정한다")
    void createFixPullRequest_경로자동보정_basename일치() {
        // 1. 브랜치 존재 확인 -> 404
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/" + BRANCH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // 2. main 브랜치 최신 SHA 조회
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs/heads/main"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "object": { "sha": "main-sha-123" } }
                        """, MediaType.APPLICATION_JSON));

        // 3. 새 브랜치 생성
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 4. 파일 SHA 조회 - 자동 보정된 FILE_PATH로 호출되어야 함
        server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH);
                    assertThat(request.getURI().getQuery()).contains("ref=" + BRANCH);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "sha": "file-sha-456" }
                        """, MediaType.APPLICATION_JSON));

        // 5. 파일 커밋 - 자동 보정된 FILE_PATH로 호출되어야 함
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/contents/" + FILE_PATH))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 6. PR 생성
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/repos/" + OWNER + "/" + REPO + "/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "html_url": "https://github.com/test-owner/test-repo/pull/11" }
                        """, MediaType.APPLICATION_JSON));

        // when - 디렉토리 없이 파일명만 전달
        String result = pullRequestTools.createFixPullRequest(
                "AiOpsAgentService.java", NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result)
                .contains("새 PR을 생성했습니다")
                .contains("https://github.com/test-owner/test-repo/pull/11");
    }

    @Test
    @DisplayName("일치하는 파일이 없으면 GitHub API 호출 없이 오류 메시지를 반환한다")
    void createFixPullRequest_경로없음_에러() {
        // when
        String result = pullRequestTools.createFixPullRequest(
                "NotFound.java", NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result).startsWith("경로를 찾을 수 없습니다:").contains("NotFound.java");
        server.verify();
    }

    @Test
    @DisplayName("동일한 파일명이 여러 경로에 존재하면 GitHub API 호출 없이 후보 목록을 반환한다")
    void createFixPullRequest_경로여러개일치_에러() throws Exception {
        // given - 동일 파일명을 다른 경로에 추가로 커밋
        String duplicatePath = "codebot/src/main/java/codebot/codebot/agent/AiOpsAgentService.java";
        Path duplicateFile = repoDir.resolve(duplicatePath);
        Files.createDirectories(duplicateFile.getParent());
        Files.writeString(duplicateFile, "package codebot.codebot.agent;\n\npublic class AiOpsAgentService {\n}\n");
        run("git", "add", "-A");
        run("git", "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "-m", "dup");

        // when
        String result = pullRequestTools.createFixPullRequest(
                "AiOpsAgentService.java", NEW_CONTENT, "MIC-12", "fix: 버그 수정", "fix: 버그 수정", "## 변경 요약\n버그를 수정했습니다.");

        // then
        assertThat(result)
                .contains("여러 개입니다")
                .contains(FILE_PATH)
                .contains(duplicatePath);
        server.verify();
    }
}
