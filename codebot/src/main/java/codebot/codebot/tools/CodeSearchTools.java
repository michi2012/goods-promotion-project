package codebot.codebot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class CodeSearchTools {

    private final RestClient githubClient;
    private final String githubOwner;
    private final String githubRepo;

    public CodeSearchTools(
            @Qualifier("githubClient") RestClient githubClient,
            @Value("${github.owner}") String githubOwner,
            @Value("${github.repo}") String githubRepo) {
        this.githubClient = githubClient;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
    }

    @Tool(description = """
            GitHub 레포지토리에서 키워드로 코드를 검색합니다 (GitHub Code Search API).
            언제 호출: 에러 메시지, 클래스명, 메서드명, 설정 키 등으로 관련 코드 위치를 찾아야 할 때.
            반환: 일치하는 파일의 경로와 GitHub URL 목록 (최대 10건). 실제 코드는 getFileContent로 조회하세요.
            실패 시: GitHub API 이상 또는 검색 결과 없음이므로, 다른 키워드로 재시도하거나 스킵하세요.
            """)
    public String searchCode(
            @ToolParam(description = "검색 키워드 (예: \"PaymentService\", \"OutOfMemoryError\")") String query) {
        try {
            String rawJson = githubClient.get()
                    .uri("/search/code?q={query}+repo:{owner}/{repo}&per_page=10",
                            query, githubOwner, githubRepo)
                    .retrieve()
                    .body(String.class);

            String summary = extractSearchResults(rawJson);
            log.info("[Tool] GitHub 코드 검색 성공: query={}", query);
            return summary;
        } catch (HttpStatusCodeException e) {
            String errorMsg = e.getResponseBodyAsString();
            log.warn("[Tool] GitHub 코드 검색 실패: status={}, body={}", e.getStatusCode(), errorMsg);
            return "GitHub 코드 검색 실패: " + e.getStatusCode() + " - " + errorMsg;
        } catch (Exception e) {
            log.warn("[Tool] GitHub 코드 검색 실패: {}", e.getMessage());
            return "GitHub 코드 검색 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            GitHub 레포지토리의 특정 파일 내용을 조회합니다.
            언제 호출: searchCode로 찾은 파일의 실제 코드를 확인해야 할 때.
            반환: 파일 내용 (텍스트, 최대 10,000자 절삭).
            실패 시: 파일 없음 또는 GitHub API 이상이므로 스킵하세요.
            """)
    public String getFileContent(
            @ToolParam(description = "레포지토리 루트 기준 파일 경로 (예: \"aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java\")") String path) {
        try {
            String content = githubClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", githubOwner, githubRepo, path)
                    .headers(headers -> headers.set(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                    .retrieve()
                    .body(String.class);

            log.info("[Tool] GitHub 파일 조회 성공: path={}", path);
            return truncate(content);
        } catch (HttpStatusCodeException e) {
            String errorMsg = e.getResponseBodyAsString();
            log.warn("[Tool] GitHub 파일 조회 실패: path={}, status={}, body={}", path, e.getStatusCode(), errorMsg);
            return "GitHub 파일 조회 실패: " + e.getStatusCode() + " - " + errorMsg;
        } catch (Exception e) {
            log.warn("[Tool] GitHub 파일 조회 실패: path={}, error={}", path, e.getMessage());
            return "GitHub 파일 조회 실패: " + e.getMessage();
        }
    }

    private String extractSearchResults(String rawJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode items = mapper.readTree(rawJson).path("items");
            if (!items.isArray() || items.isEmpty()) {
                return "검색 결과 없음";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : items) {
                String path = item.path("path").asText("?");
                String url = item.path("html_url").asText("?");
                sb.append(path).append(" | ").append(url).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "검색 결과 파싱 실패: " + e.getMessage();
        }
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
