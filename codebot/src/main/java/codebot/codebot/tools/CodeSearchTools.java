package codebot.codebot.tools;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CodeSearchTools {

    private final Path repoPath;

    public CodeSearchTools(@Value("${codebot.repo.local-path:/repo/current}") String repoPath) {
        this.repoPath = Path.of(repoPath);
    }

    @Tool(description = """
            로컬에 동기화된 레포지토리에서 키워드로 코드를 검색합니다 (git grep).
            언제 호출: 에러 메시지, 클래스명, 메서드명, 설정 키 등으로 관련 코드 위치를 찾아야 할 때.
            반환: "경로:라인번호:내용" 형식의 일치 결과 목록. 실제 코드는 getFileContent로 조회하세요.
            실패 시: 검색 결과 없음이므로 다른 키워드로 재시도하거나 스킵하세요.
            """)
    public String searchCode(
            @ToolParam(description = "검색 키워드 (예: \"PaymentService\", \"OutOfMemoryError\")") String query) {
        try {
            String result = runGitGrep(query);
            String pathMatches = findPathMatches(query);
            if (!pathMatches.isEmpty()) {
                result = result + "\n\n파일 경로 일치:\n" + pathMatches;
            }
            log.info("[Tool] 코드 검색 성공: query={}", query);
            return result;
        } catch (Exception e) {
            log.warn("[Tool] 코드 검색 실패: query={}, error={}", query, e.getMessage());
            return "코드 검색 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            로컬에 동기화된 레포지토리의 특정 파일 내용을 조회합니다.
            언제 호출: searchCode로 찾은 파일의 실제 코드를 확인해야 할 때.
            반환: 파일 내용 (텍스트, 최대 10,000자 절삭).
            실패 시: 파일 없음 또는 읽기 오류이므로 스킵하세요.
            """)
    public String getFileContent(
            @ToolParam(description = "레포지토리 루트 기준 파일 경로. 이전 단계(searchCode)에서 확인한 실제 경로를 그대로 사용하세요.") String path) {
        FileLookup lookup = readFile(path);
        if (lookup.errorMessage() != null) {
            log.warn("[Tool] 파일 조회 실패: path={}, error={}", path, lookup.errorMessage());
            return lookup.errorMessage();
        }
        log.info("[Tool] 파일 조회 성공: path={}", path);
        return truncate(lookup.content());
    }

    @Tool(description = """
            파일의 현재 내용과 수정 제안 내용(newContent)을 비교해 unified diff를 반환합니다.
            언제 호출: createFixPullRequest 호출 전, 어떤 부분이 바뀌는지 사용자에게 보여줘야 할 때.
            반환: unified diff 형식의 텍스트 (최대 10,000자 절삭). 변경 사항이 없으면 "응답 데이터 없음"을 반환합니다.
            실패 시: getFileContent와 동일한 오류 메시지를 반환하므로 그대로 사용자에게 안내하세요.
            """)
    public String previewDiff(
            @ToolParam(description = "레포지토리 루트 기준 파일 경로. 이전 단계에서 확인한 실제 경로를 그대로 사용하세요.") String filePath,
            @ToolParam(description = "수정 제안 파일 전체 내용 (newContent)") String newContent) {
        FileLookup lookup = readFile(filePath);
        if (lookup.errorMessage() != null) {
            log.warn("[Tool] diff 미리보기 실패: path={}, error={}", filePath, lookup.errorMessage());
            return lookup.errorMessage();
        }

        List<String> oldLines = lookup.content().lines().collect(Collectors.toList());
        List<String> newLines = newContent.lines().collect(Collectors.toList());
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(filePath, filePath, oldLines, patch, 3);

        log.info("[Tool] diff 미리보기 성공: path={}", filePath);
        String diffText = truncate(String.join("\n", unifiedDiff));
        if (diffText.equals("응답 데이터 없음")) {
            return diffText;
        }
        return "```diff\n" + diffText + "\n```";
    }

    private record FileLookup(String content, String errorMessage) {
    }

    private FileLookup readFile(String path) {
        try {
            Path resolved = repoPath.resolve(path).normalize();
            if (!resolved.startsWith(repoPath)) {
                return new FileLookup(null, "허용되지 않은 경로입니다: " + path);
            }

            if (Files.exists(resolved)) {
                return new FileLookup(Files.readString(resolved), null);
            }

            return readByBasename(path);
        } catch (Exception e) {
            return new FileLookup(null, "파일 조회 실패: " + e.getMessage());
        }
    }

    private FileLookup readByBasename(String path) throws Exception {
        String basename = Path.of(path).getFileName().toString();
        List<String> matches = listFiles().stream()
                .filter(line -> Path.of(line).getFileName().toString().equalsIgnoreCase(basename))
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return new FileLookup(null, "파일 조회 실패: 파일을 찾을 수 없습니다: " + path);
        }
        if (matches.size() > 1) {
            return new FileLookup(null, "파일명이 일치하는 경로가 여러 개입니다. 정확한 경로를 지정해주세요:\n" + String.join("\n", matches));
        }

        String matchedPath = matches.get(0);
        log.info("[Tool] 경로 자동 보정: 요청={}, 실제={}", path, matchedPath);
        return new FileLookup(Files.readString(repoPath.resolve(matchedPath).normalize()), null);
    }

    private String findPathMatches(String query) throws Exception {
        String lowerQuery = query.toLowerCase();
        return listFiles().stream()
                .filter(line -> line.toLowerCase().contains(lowerQuery))
                .collect(Collectors.joining("\n"));
    }

    private List<String> listFiles() throws Exception {
        List<String> command = List.of("git", "-C", repoPath.toString(), "ls-files");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> files;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            files = reader.lines().collect(Collectors.toList());
        }
        process.waitFor();
        return files;
    }

    private String runGitGrep(String query) throws Exception {
        List<String> command = List.of("git", "-C", repoPath.toString(), "grep", "-n", "-i", "-e", query);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode == 1) {
            return "검색 결과 없음";
        }
        if (exitCode != 0) {
            throw new RuntimeException("git grep 종료 코드 " + exitCode + ": " + output);
        }
        return output.isBlank() ? "검색 결과 없음" : output;
    }

    private String truncate(String data) {
        if (data == null || data.isBlank()) {
            return "응답 데이터 없음";
        }
        int maxLength = 10000;
        if (data.length() > maxLength) {
            return data.substring(0, maxLength) + "\n...[데이터가 너무 길어 시스템 보호를 위해 절삭되었습니다]...";
        }
        return data;
    }
}
