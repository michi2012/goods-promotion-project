package codebot.codebot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CodeSearchTools 단위 테스트")
class CodeSearchToolsTest {

    @TempDir
    Path repoDir;

    private CodeSearchTools codeSearchTools;

    @BeforeEach
    void setUp() throws Exception {
        run("git", "init", "-q");

        Files.writeString(repoDir.resolve("AiOpsAgentService.java"),
                "package aiops.aiops.agent;\n\npublic class AiOpsAgentService {\n}\n");
        Files.createDirectories(repoDir.resolve("sub"));
        Files.writeString(repoDir.resolve("sub").resolve("Nested.java"), "class Nested {}\n");
        Files.createDirectories(repoDir.resolve("dup1"));
        Files.createDirectories(repoDir.resolve("dup2"));
        Files.writeString(repoDir.resolve("dup1").resolve("Dup.java"), "class Dup1 {}\n");
        Files.writeString(repoDir.resolve("dup2").resolve("Dup.java"), "class Dup2 {}\n");

        run("git", "add", "-A");
        run("git", "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "-m", "init");

        codeSearchTools = new CodeSearchTools(repoDir.toString());
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(repoDir.toFile()).start();
        process.waitFor();
    }

    @Test
    @DisplayName("코드 검색 성공 시 경로:라인:내용 형식으로 반환한다")
    void searchCode_성공() {
        // when
        String result = codeSearchTools.searchCode("AiOpsAgentService");

        // then
        assertThat(result).contains("AiOpsAgentService.java");
    }

    @Test
    @DisplayName("검색 결과가 없으면 결과 없음 메시지를 반환한다")
    void searchCode_결과없음() {
        // when
        String result = codeSearchTools.searchCode("존재하지않는키워드");

        // then
        assertThat(result).isEqualTo("검색 결과 없음");
    }

    @Test
    @DisplayName("쿼리가 파일 경로에 일치하면 파일 경로 일치 섹션을 추가한다")
    void searchCode_파일경로일치() {
        // when
        String result = codeSearchTools.searchCode("AiOpsAgentService.java");

        // then
        assertThat(result).contains("파일 경로 일치:");
        assertThat(result).contains("AiOpsAgentService.java");
    }

    @Test
    @DisplayName("파일 내용 조회 성공 시 파일 내용을 반환한다")
    void getFileContent_성공() {
        // when
        String result = codeSearchTools.getFileContent("sub/Nested.java");

        // then
        assertThat(result).isEqualTo("class Nested {}\n");
    }

    @Test
    @DisplayName("파일이 없으면 실패 메시지를 반환한다")
    void getFileContent_파일없음() {
        // when
        String result = codeSearchTools.getFileContent("NotFound.java");

        // then
        assertThat(result).startsWith("파일 조회 실패:");
    }

    @Test
    @DisplayName("경로 없이 파일명만 주어지면 git ls-files로 경로를 찾아 내용을 반환한다")
    void getFileContent_파일명만_단일매치() {
        // when
        String result = codeSearchTools.getFileContent("Nested.java");

        // then
        assertThat(result).isEqualTo("class Nested {}\n");
    }

    @Test
    @DisplayName("동일한 파일명이 여러 경로에 존재하면 후보 경로 목록을 반환한다")
    void getFileContent_파일명만_다중매치() {
        // when
        String result = codeSearchTools.getFileContent("Dup.java");

        // then
        assertThat(result).contains("dup1/Dup.java");
        assertThat(result).contains("dup2/Dup.java");
    }

    @Test
    @DisplayName("상위 디렉토리로 이탈하는 경로는 거부한다")
    void getFileContent_경로이탈() {
        // when
        String result = codeSearchTools.getFileContent("../outside.txt");

        // then
        assertThat(result).startsWith("허용되지 않은 경로입니다:");
    }

    @Test
    @DisplayName("변경 내용이 있으면 ```diff 코드 블록으로 감싼 unified diff를 반환한다")
    void previewDiff_변경있음() {
        // when
        String result = codeSearchTools.previewDiff("sub/Nested.java", "class Nested {\n    void foo() {}\n}\n");

        // then
        assertThat(result).startsWith("```diff\n");
        assertThat(result).endsWith("\n```");
        assertThat(result).contains("@@");
        assertThat(result).contains("-class Nested {}");
        assertThat(result).contains("+class Nested {");
    }

    @Test
    @DisplayName("변경 내용이 없으면 응답 데이터 없음을 반환한다")
    void previewDiff_변경없음() {
        // when
        String result = codeSearchTools.previewDiff("sub/Nested.java", "class Nested {}\n");

        // then
        assertThat(result).isEqualTo("응답 데이터 없음");
    }

    @Test
    @DisplayName("파일이 없으면 실패 메시지를 반환한다")
    void previewDiff_파일없음() {
        // when
        String result = codeSearchTools.previewDiff("NotFound.java", "new content");

        // then
        assertThat(result).startsWith("파일 조회 실패:");
    }
}
