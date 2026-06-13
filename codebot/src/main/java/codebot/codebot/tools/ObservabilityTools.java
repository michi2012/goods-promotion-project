package codebot.codebot.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
public class ObservabilityTools {

    private final RestClient lokiClient;
    private final RestClient tempoClient;
    private final RestClient prometheusClient;

    public ObservabilityTools(
            @Qualifier("lokiClient") RestClient lokiClient,
            @Qualifier("tempoClient") RestClient tempoClient,
            @Qualifier("prometheusClient") RestClient prometheusClient) {
        this.lokiClient = lokiClient;
        this.tempoClient = tempoClient;
        this.prometheusClient = prometheusClient;
    }

    @Tool(description = """
            Loki에서 특정 서비스의 로그를 조회합니다.
            언제 호출: 코드 조사 중 실제 에러 로그·스택트레이스·traceId 확인이 필요할 때만 (필요시 조회).
            반환: 최근 N분간의 로그 라인 (최대 50건, 10,000자 초과 시 자동 절삭).
            실패 시: Loki 서버 장애일 수 있으므로 이 도구 호출을 스킵하고 코드 조사를 계속하세요.
            """)
    public String queryLokiLogs(
            @ToolParam(description = "조회할 서비스 이름 (예: server-a, server-b, server-c)") String service,
            @ToolParam(description = "LogQL 필터 표현식 (예: |= \"ERROR\", 필터 없음: 빈 문자열)") String query,
            @ToolParam(description = "조회 범위. 현재 시각 기준 몇 분 전까지 조회할지 (예: 30)") int minutes) {
        try {
            long end = Instant.now().getEpochSecond() * 1_000_000_000L;
            long start = end - (long) minutes * 60 * 1_000_000_000L;

            String logqlExpr = "{app=\"" + service + "\"}";
            if (query != null && !query.isBlank()) {
                logqlExpr += " " + query;
            }

            Map<String, Object> uriVariables = Map.of(
                    "queryVal", logqlExpr,
                    "startVal", start,
                    "endVal", end
            );

            String response = lokiClient.get()
                    .uri("/loki/api/v1/query_range?query={queryVal}&start={startVal}&end={endVal}&limit=50", uriVariables)
                    .retrieve()
                    .body(String.class);

            log.info("[Tool] Loki query 성공: service={}", service);
            return truncate(response);
        } catch (Exception e) {
            log.warn("[Tool] Loki 조회 실패: {}", e.getMessage());
            return "Loki 조회 실패 (서버 장애 가능성): " + e.getMessage() + " — 스킵하고 코드 조사를 계속하세요.";
        }
    }

    @Tool(description = """
            Tempo에서 특정 트레이스 ID의 분산 추적 정보를 조회합니다.
            언제 호출: Loki 로그에서 traceId를 발견했고, 어느 서비스·구간에서 지연·에러가 발생했는지 확인이 필요할 때.
            반환: 해당 요청의 전체 스팬(Span) 타임라인.
            실패 시: Tempo 서버 장애일 수 있으므로 이 도구 호출을 스킵하고 코드 조사를 계속하세요.
            """)
    public String queryTempoTrace(
            @ToolParam(description = "16자리 이상의 16진수 문자열 traceId") String traceId) {
        try {
            String response = tempoClient.get()
                    .uri("/api/traces/{traceId}", traceId)
                    .retrieve()
                    .body(String.class);

            log.info("[Tool] Tempo query 성공: traceId={}", traceId);
            return truncate(response);
        } catch (Exception e) {
            log.warn("[Tool] Tempo 조회 실패: {}", e.getMessage());
            return "Tempo 조회 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            Prometheus에서 PromQL 표현식으로 메트릭을 즉시 조회합니다.
            언제 호출: 코드 조사 중 에러율·레이턴시·리소스 사용률 등 수치 근거가 필요할 때만 (필요시 조회).
            반환: 현재 시점의 메트릭 값 (JSON).
            실패 시: Prometheus 서버 장애이므로 스킵하고 코드 조사를 계속하세요.
            """)
    public String queryPrometheusMetrics(
            @ToolParam(description = "조회할 PromQL 표현식 (예: rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]))") String promql) {
        try {
            Map<String, String> uriVariables = Map.of("queryVal", promql);
            String response = prometheusClient.get()
                    .uri("/api/v1/query?query={queryVal}", uriVariables)
                    .retrieve()
                    .body(String.class);

            log.info("[Tool] Prometheus query 성공");
            return truncate(response);
        } catch (Exception e) {
            log.warn("[Tool] Prometheus 조회 실패: {}", e.getMessage());
            return "Prometheus 조회 실패: " + e.getMessage();
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
