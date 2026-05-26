package mcp.mcp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mcp.mcp.slack.SlackNotificationService;
import mcp.mcp.tools.ObservabilityTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOpsAgentService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObservabilityTools observabilityTools;
    private final SlackNotificationService slackService;

    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            당신은 시니어 SRE 엔지니어입니다. Prometheus 알람을 받으면 아래 절차를 순서대로 수행하세요.

            ## 분석 절차
            1. payload에서 alertname, severity, tier, summary, description을 파악하라.
            2. queryPrometheusMetrics를 호출하여 현재 에러율과 핵심 지표를 확인하라.
               - 에러율: rate(http_server_requests_seconds_count{status=~"5.."}[5m])
               - 가용성: sum(rate(http_server_requests_seconds_count{status!~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) * 100
            3. 대상을 식별하라:
               - 만약 최초 payload 라벨에 'app' 또는 'job'이 명시되어 있다면 그 값을 사용하라.
               - 만약 payload에 둘 다 없다면, 2번 단계의 Prometheus 메트릭 조회 결과(라벨 목록)를 분석하여 가장 에러가 심한 서비스명(예: server-a)을 동적으로 찾아내어 대상으로 삼아라.
            4. 3번에서 식별한 서비스명을 파라미터로 사용하여 queryLokiLogs를 호출하고 최근 5분 ERROR 로그를 조회하라.
               단, Loki 조회에 실패하더라도 분석을 중단하지 말고 수집된 메트릭만으로 다음 단계를 계속 진행하라.
            5. 4번에서 조회한 로그 텍스트 안에 'traceId' 또는 'trace_id'라는 문자열이 포함되어 있다면, 해당 ID 값을 추출하여 queryTempoTrace를 호출하라. 없으면 스킵하라.
            6. 수집한 모든 정보를 바탕으로 아래 형식의 보고서를 작성하라. 데이터로 뒷받침되지 않는 추측은 절대 쓰지 마라.

            ## 보고서 형식 (주의: 슬랙 문법이므로 굵은 글씨는 반드시 * 한 개만 사용할 것)
            ## 🚨 [*severity*] alertname
            *요약*: summary 내용 그대로
            *원인*: 수집된 메트릭, 로그, 트레이스 기반의 구체적 원인
            *영향 범위*: 영향받는 서비스와 기능
            *권장 조치*: 단계별 조치 1~3개
            *핵심 지표*: 주요 수치 요약 1~3개
            """;

    public void analyze(String alertPayload) {
        log.info("[AIOps] 알람 수신 및 분석 시작");
        try {
            // 1. JSON 파싱하여 상태값 확인
            JsonNode rootNode = objectMapper.readTree(alertPayload);
            String status = rootNode.path("status").asText();

            // 2. 정상 회복 상태면 AI 분석을 건너뛰고 즉시 종료
            if ("resolved".equalsIgnoreCase(status)) {
                log.info("[AIOps] 정상 회복 확인. AI 분석 스킵.");

                // 알람 이름을 추출하여 깔끔한 복구 메시지 생성
                String alertName = rootNode.path("alerts")
                                           .path(0)
                                           .path("labels")
                                           .path("alertname")
                                           .asText("알 수 없는 알람");

                slackService.send("🟢 *[정상 회복]* `" + alertName + "` 장애가 성공적으로 복구되었습니다.");
                return;
            }

            // 3. Firing 상태일 경우에만 AI 호출
            log.info("[AIOps] Firing 상태 확인. AI 도구 호출 및 원인 분석 진행");
            String report = chatClientBuilder.build()
                                             .prompt()
                                             .system(SYSTEM_PROMPT)
                                             .user(alertPayload)
                                             .tools(observabilityTools)
                                             .call()
                                             .content();

            log.info("[AIOps] 분석 완료, Slack 발송");
            slackService.send(report);

        } catch (Exception e) {
            log.error("[AIOps] 분석 실패: {}", e.getMessage());
        }
    }
}