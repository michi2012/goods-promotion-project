package aiops.aiops.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Service
public class LinearAuditService {

    private final RestClient linearClient;
    private final ObjectMapper objectMapper;
    private final String teamId;

    public LinearAuditService(
            @Qualifier("linearClient") RestClient linearClient,
            @Value("${linear.team-id}") String teamId) {
        this.linearClient = linearClient;
        this.objectMapper = new ObjectMapper();
        this.teamId = teamId;
    }

    public String createAuditTicket(String actionType, String paramsJson, String reason, String executionResult) {
        try {
            String title = "[감사] " + actionType + " 자동조치 실행";
            String description = """
                    ## 실행된 자동조치
                    - 액션 타입: %s
                    - 파라미터: %s
                    - 트리거 사유: %s

                    ## 실행 결과
                    %s
                    """.formatted(actionType, paramsJson, reason, executionResult);

            Map<String, Object> input = Map.of(
                    "title", title,
                    "description", description,
                    "teamId", teamId
            );

            String mutation = """
                    mutation IssueCreate($input: IssueCreateInput!) {
                      issueCreate(input: $input) {
                        success
                        issue { identifier url }
                      }
                    }
                    """;

            JsonNode result = execute(mutation, Map.of("input", input));
            JsonNode issueCreate = result.path("data").path("issueCreate");
            if (!issueCreate.path("success").asBoolean(false)) {
                throw new IllegalStateException("issueCreate 응답에 success=true가 없습니다: " + result);
            }

            String identifier = issueCreate.path("issue").path("identifier").asText("?");
            String url = issueCreate.path("issue").path("url").asText("?");

            log.info("[LinearAudit] 감사 티켓 생성 성공: {}", identifier);
            return "Linear 감사 티켓 생성 완료: " + identifier + " — " + url;
        } catch (Exception e) {
            log.warn("[LinearAudit] 감사 티켓 생성 실패: {}", e.getMessage());
            return "Linear 감사 티켓 생성 실패: " + e.getMessage();
        }
    }

    private JsonNode execute(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = Map.of("query", query, "variables", variables);
        String rawJson = linearClient.post()
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode result = objectMapper.readTree(rawJson);
        JsonNode errors = result.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Linear GraphQL 오류: " + errors);
        }
        return result;
    }
}
