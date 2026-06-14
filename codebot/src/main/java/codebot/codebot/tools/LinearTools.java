package codebot.codebot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LinearTools {

    private static final String PR_CHECKLIST = """

            ## PR 전 체크리스트
            - [ ] 테스트 작성 및 통과 확인
            - [ ] API/인터페이스 변경 시 관련 문서 갱신
            - [ ] 민감 정보(비밀번호, 토큰 등) 로그/응답 노출 여부 확인
            """;

    private final RestClient linearClient;
    private final ObjectMapper objectMapper;
    private final String teamId;

    public LinearTools(
            @Qualifier("linearClient") RestClient linearClient,
            @Value("${linear.team-id}") String teamId) {
        this.linearClient = linearClient;
        this.objectMapper = new ObjectMapper();
        this.teamId = teamId;
    }

    @Tool(description = """
            조사 결과를 바탕으로 Linear에 원인 분석(RCA) 이슈를 생성합니다.
            언제 호출: 코드 조사가 끝나고 원인 분석 결과를 티켓으로 남겨야 할 때 (조사당 한 번만 호출).
            반환: 생성된 이슈의 식별자(예: MIC-42)와 URL.
            실패 시: Linear API 인증/네트워크 문제이므로 이슈 생성 실패를 알리고 조사 결과 텍스트로 대신 응답하세요.
            """)
    public String createIssue(
            @ToolParam(description = "이슈 제목 (한 문장으로 압축)") String title,
            @ToolParam(description = """
                    이슈 본문(markdown). 배경, 증상, 근본 원인(미확인 부분은 "가설:"로 표기), 조사 근거를 포함하세요.
                    'PR 전 체크리스트' 섹션은 자동으로 추가되므로 포함하지 마세요.
                    """) String description,
            @ToolParam(description = "도메인 라벨 이름 (주문/결제/프로모션/유저 중 하나)") String domainLabel,
            @ToolParam(description = "직무 라벨 이름 (백엔드/프론트엔드/인프라 중 하나)") String roleLabel) {
        try {
            String assigneeId = resolveViewerId();
            String domainLabelId = resolveLabelId(domainLabel);
            String roleLabelId = resolveLabelId(roleLabel);

            Map<String, Object> input = Map.of(
                    "title", title,
                    "description", description + PR_CHECKLIST,
                    "teamId", teamId,
                    "assigneeId", assigneeId,
                    "labelIds", List.of(domainLabelId, roleLabelId)
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

            log.info("[Tool] Linear 이슈 생성 성공: {}", identifier);
            return "Linear 이슈 생성 완료: " + identifier + " — " + url;
        } catch (Exception e) {
            log.warn("[Tool] Linear 이슈 생성 실패: {}", e.getMessage());
            return "Linear 이슈 생성 실패: " + e.getMessage();
        }
    }

    private String resolveViewerId() throws Exception {
        JsonNode result = execute("query { viewer { id } }", Map.of());
        return result.path("data").path("viewer").path("id").asText();
    }

    private String resolveLabelId(String labelName) throws Exception {
        String query = """
                query($teamId: String!, $name: String!) {
                  issueLabels(filter: { team: { id: { eq: $teamId } }, name: { eq: $name } }) {
                    nodes { id }
                  }
                }
                """;
        JsonNode result = execute(query, Map.of("teamId", teamId, "name", labelName));
        JsonNode nodes = result.path("data").path("issueLabels").path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("라벨을 찾을 수 없습니다: " + labelName);
        }
        return nodes.get(0).path("id").asText();
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
