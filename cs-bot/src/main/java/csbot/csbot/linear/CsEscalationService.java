package csbot.csbot.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import csbot.csbot.classification.CsClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Service
public class CsEscalationService {

    private final RestClient linearClient;
    private final ObjectMapper objectMapper;
    private final String teamId;

    public CsEscalationService(
            @Qualifier("linearClient") RestClient linearClient,
            @Value("${linear.team-id}") String teamId) {
        this.linearClient = linearClient;
        this.objectMapper = new ObjectMapper();
        this.teamId = teamId;
    }

    public String createEscalationTicket(String summary, String loginId, CsClassification.Urgency urgency) {
        try {
            String title = "[CS 에스컬레이션] " + summary;
            String description = """
                    ## 고객 문의 요약
                    %s

                    ## 요청자
                    %s

                    ## 긴급도
                    %s
                    """.formatted(summary, loginId, urgency);

            Map<String, Object> input = Map.of(
                    "title", title,
                    "description", description,
                    "teamId", teamId,
                    "priority", mapPriority(urgency)
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

            log.info("[CsEscalation] 에스컬레이션 티켓 생성 성공: {}", identifier);
            return "상담원에게 문의가 접수되었습니다. 접수번호: " + identifier + " — " + url;
        } catch (Exception e) {
            log.warn("[CsEscalation] 에스컬레이션 티켓 생성 실패: {}", e.getMessage());
            return "상담원 연결 접수에 실패했습니다. 잠시 후 다시 시도해 주세요: " + e.getMessage();
        }
    }

    private int mapPriority(CsClassification.Urgency urgency) {
        // Linear priority: 0=No priority, 1=Urgent, 2=High, 3=Medium, 4=Low
        return switch (urgency) {
            case HIGH -> 1;
            case MEDIUM -> 3;
            case LOW -> 4;
        };
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
