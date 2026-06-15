package codebot.codebot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PullRequestTools {

    private static final String PR_CHECKLIST = """


            ## PR 전 체크리스트
            - [ ] 테스트 작성 및 통과 확인
            - [ ] API/인터페이스 변경 시 관련 문서 갱신
            - [ ] 민감 정보(비밀번호, 토큰 등) 로그/응답 노출 여부 확인""";

    private static final String PR_NOTICE = """


            ---
            ⚠️ codebot이 자동 생성한 PR입니다. 머지 전 리뷰·테스트가 필요합니다.""";

    private final RestClient githubClient;
    private final ObjectMapper objectMapper;
    private final String githubOwner;
    private final String githubRepo;
    private final Path repoPath;

    public PullRequestTools(
            @Qualifier("githubClient") RestClient githubClient,
            @Value("${github.owner}") String githubOwner,
            @Value("${github.repo}") String githubRepo,
            @Value("${codebot.repo.local-path:/repo/current}") String repoPath) {
        this.githubClient = githubClient;
        this.objectMapper = new ObjectMapper();
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.repoPath = Path.of(repoPath);
    }

    @Tool(description = """
            지정된 단일 파일의 내용을 교체하는 커밋을 만들고 GitHub PR을 생성합니다.
            언제 호출: 같은 Slack 스레드에서 사용자가 "고쳐서 PR 올려줘"처럼 코드 수정을 명시적으로 지시했고,
            수정 대상이 단일 파일로 명확히 식별될 때만 호출하세요. 여러 파일 수정이 필요하다면 이 도구를 호출하지 말고
            어떤 파일들을 수정해야 하는지 안내하세요.
            브랜치명은 issueIdentifier로 결정됩니다 (feature/{issueIdentifier 소문자}-codebot-fix). 같은 이슈에 대해
            다시 호출하면 같은 브랜치에 커밋이 추가되고, 이미 열린 PR이 있으면 새 PR을 만들지 않고 그 PR을 그대로 반환합니다.
            반환: 생성/갱신된 PR URL 또는 안내 메시지.
            실패 시: GitHub API 오류 메시지를 그대로 반환하므로 사용자에게 안내하세요.
            """)
    public String createFixPullRequest(
            @ToolParam(description = "레포지토리 루트 기준 수정할 파일 경로. 이전 단계(getFileContent/searchCode/previewDiff)에서 확인한 실제 경로를 그대로 사용하세요.") String filePath,
            @ToolParam(description = "교체될 파일 전체 내용") String newContent,
            @ToolParam(description = "Linear 이슈 식별자 (예: \"MIC-12\") — 브랜치명 결정 및 PR의 Closes 연동에 사용") String issueIdentifier,
            @ToolParam(description = "커밋 메시지 (예: \"fix: OrderService 무한루프 수정\")") String commitMessage,
            @ToolParam(description = "PR 제목 (신규 PR 생성 시에만 사용)") String prTitle,
            @ToolParam(description = """
                    PR 본문(마크다운, 신규 PR 생성 시에만 사용). 아래 형식을 그대로 따르세요 ("관련 이슈" 섹션은 자동으로 추가되므로 포함하지 마세요):

                    ## 변경 요약
                    {무엇을, 왜 변경했는지 1-3문장}

                    ## 테스트 방법
                    {어떻게 검증했는지}

                    ## 영향도 및 주의사항
                    {이번 변경으로 영향을 받는 다른 기능/API/이벤트, 주의할 점}
                    """) String prBody) {
        if (isProtectedPath(filePath)) {
            return "보안상 \"" + filePath + "\" 경로는 codebot이 수정할 수 없습니다 (CI 설정/환경변수 파일 등). 직접 수정해주세요.";
        }

        String resolvedPath;
        try {
            resolvedPath = resolveFilePath(filePath);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[Tool] 경로 확인 실패: {}", e.getMessage());
            return "경로 확인 중 오류가 발생했습니다: " + e.getMessage();
        }

        if (isProtectedPath(resolvedPath)) {
            return "보안상 \"" + resolvedPath + "\" 경로는 codebot이 수정할 수 없습니다 (CI 설정/환경변수 파일 등). 직접 수정해주세요.";
        }

        try {
            String branch = "feature/" + issueIdentifier.toLowerCase() + "-codebot-fix";
            boolean isNewBranch = !branchExists(branch);

            if (isNewBranch) {
                createBranch(branch);
            }

            String fileSha = getFileSha(resolvedPath, branch);
            commitFile(resolvedPath, newContent, commitMessage, branch, fileSha);

            if (isNewBranch) {
                String prUrl = createPullRequest(branch, issueIdentifier, prTitle, prBody);
                log.info("[Tool] PR 생성 성공: branch={}, prUrl={}", branch, prUrl);
                return "새 PR을 생성했습니다: " + prUrl;
            }

            String existingPrUrl = findOpenPullRequest(branch);
            if (existingPrUrl != null) {
                log.info("[Tool] 기존 브랜치에 추가 커밋 반영: branch={}, prUrl={}", branch, existingPrUrl);
                return "기존 브랜치(" + branch + ")에 추가 커밋을 반영했습니다. 기존 PR: " + existingPrUrl;
            }

            log.info("[Tool] 기존 브랜치에 커밋했으나 열린 PR 없음: branch={}", branch);
            return "브랜치 " + branch + "에 커밋을 반영했지만 열린 PR을 찾지 못했습니다. "
                   + "이전 PR이 이미 머지되었거나 닫혔을 수 있으니 직접 확인해주세요.";
        } catch (Exception e) {
            log.warn("[Tool] PR 생성/커밋 실패: {}", e.getMessage());
            return "PR 생성/커밋 실패: " + e.getMessage();
        }
    }

    private boolean isProtectedPath(String filePath) {
        String normalized = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        return normalized.startsWith(".github/") || normalized.equals(".env") || normalized.startsWith(".env.");
    }

    private String resolveFilePath(String filePath) throws Exception {
        String normalized = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        List<String> files = listFiles();
        if (files.contains(normalized)) {
            return normalized;
        }

        String basename = Path.of(normalized).getFileName().toString();
        List<String> matches = files.stream()
                .filter(line -> Path.of(line).getFileName().toString().equalsIgnoreCase(basename))
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("경로를 찾을 수 없습니다: " + filePath);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("파일명이 일치하는 경로가 여러 개입니다. 정확한 경로를 지정해주세요:\n" + String.join("\n", matches));
        }

        String matchedPath = matches.get(0);
        log.info("[Tool] 경로 자동 보정: 요청={}, 실제={}", filePath, matchedPath);
        return matchedPath;
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

    private boolean branchExists(String branch) {
        try {
            githubClient.get()
                    .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}", githubOwner, githubRepo, branch)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private void createBranch(String branch) throws Exception {
        String mainRefJson = githubClient.get()
                .uri("/repos/{owner}/{repo}/git/refs/heads/main", githubOwner, githubRepo)
                .retrieve()
                .body(String.class);
        String mainSha = objectMapper.readTree(mainRefJson).path("object").path("sha").asText();

        Map<String, String> body = Map.of(
                "ref", "refs/heads/" + branch,
                "sha", mainSha);

        githubClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", githubOwner, githubRepo)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String getFileSha(String filePath, String branch) throws Exception {
        String json = githubClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}?ref={branch}", githubOwner, githubRepo, filePath, branch)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(json).path("sha").asText();
    }

    private void commitFile(String filePath, String content, String message, String branch, String sha) {
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        Map<String, String> body = Map.of(
                "message", message,
                "content", encodedContent,
                "branch", branch,
                "sha", sha);

        githubClient.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", githubOwner, githubRepo, filePath)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String createPullRequest(String branch, String issueIdentifier, String prTitle, String prBody) throws Exception {
        Map<String, String> body = Map.of(
                "title", prTitle,
                "head", branch,
                "base", "main",
                "body", prBody + "\n\n## 관련 이슈\nCloses " + issueIdentifier + PR_CHECKLIST + PR_NOTICE);

        String responseJson = githubClient.post()
                .uri("/repos/{owner}/{repo}/pulls", githubOwner, githubRepo)
                .body(body)
                .retrieve()
                .body(String.class);

        return objectMapper.readTree(responseJson).path("html_url").asText();
    }

    private String findOpenPullRequest(String branch) throws Exception {
        String responseJson = githubClient.get()
                .uri("/repos/{owner}/{repo}/pulls?head={head}&state=open", githubOwner, githubRepo, githubOwner + ":" + branch)
                .retrieve()
                .body(String.class);

        JsonNode results = objectMapper.readTree(responseJson);
        if (results.isArray() && !results.isEmpty()) {
            return results.get(0).path("html_url").asText();
        }
        return null;
    }
}
