package codebot.codebot.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.List;

@Slf4j
@Component
public class CodeSearchTools {

    private final RestClient githubClient;
    private final String githubOwner;
    private final String githubRepo;
    private final int maxContentLength;

    public CodeSearchTools(
            @Qualifier("githubClient") RestClient githubClient,
            @Value("${github.owner}") String githubOwner,
            @Value("${github.repo}") String githubRepo,
            @Value("${github.max-content-length:10000}") int maxContentLength) {
        this.githubClient = githubClient;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.maxContentLength = maxContentLength;
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
            SearchResponse response = githubClient.get()
                    .uri("/search/code?q={query}+repo:{owner}/{repo}&per_page=10",
                            query, githubOwner, githubRepo)
                    .retrieve()
                    .body(SearchResponse.class);

            return formatSearchResults(response);
        } catch (RestClientException e) {
            log.warn("[Tool] GitHub 코드 검색 실패: {}", e.getMessage());
            return "GitHub 코드 검색 실패: " + e.getMessage();
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
        try {
            String content = githubClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", githubOwner, githubRepo, path)
                    .headers(headers -> headers.set(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                    .retrieve()
                    .body(String.class);

            return truncate(content);
        } catch (RestClientException e) {
            log.warn("[Tool] GitHub 파일 조회 실패: path={}, error={}", path, e.getMessage());
            return "GitHub 파일 조회 실패: " + e.getMessage();
        }
    }

    private String formatSearchResults(SearchResponse response) {
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return "검색 결과 없음";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchItem item : response.items()) {
            sb.append(item.path()).append(" | ").append(item.url()).append("\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String data) {
        if (data == null || data.isBlank()) {
            return "응답 데이터 없음";
        }
        if (data.length() > maxContentLength) {
            return data.substring(0, maxContentLength) + "\n...[데이터가 너무 길어 시스템 보호를 위해 절삭되었습니다]...";
        }
        return data;
    }

    private record SearchResponse(@JsonProperty("items") List<SearchItem> items) {}
    private record SearchItem(@JsonProperty("path") String path, @JsonProperty("html_url") String url) {}
}
