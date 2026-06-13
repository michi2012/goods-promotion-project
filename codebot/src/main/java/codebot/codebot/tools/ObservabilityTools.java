package codebot.codebot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ObservabilityTools {

    private final RestClient lokiClient;
    private final RestClient tempoClient;
    private final RestClient prometheusClient;
    private final RestClient pyroscopeClient;

    public ObservabilityTools(
            @Qualifier("lokiClient") RestClient lokiClient,
            @Qualifier("tempoClient") RestClient tempoClient,
            @Qualifier("prometheusClient") RestClient prometheusClient,
            @Qualifier("pyroscopeClient") RestClient pyroscopeClient) {
        this.lokiClient = lokiClient;
        this.tempoClient = tempoClient;
        this.prometheusClient = prometheusClient;
        this.pyroscopeClient = pyroscopeClient;
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
            Pyroscope에서 특정 서비스의 CPU 프로파일링 데이터를 조회해 핫스팟 메서드(자체 실행 시간 기준 상위 N개)를 추출합니다.
            언제 호출: 운영 상태 확인 단계에서 응답 지연·CPU 사용량 이슈가 의심될 때, 그 서비스 내부의 어떤 코드(메서드)가
            병목인지 좁혀볼 때.
            반환: "자체 CPU 시간 비율(%) | 메서드명" 형식의 상위 핫스팟 목록 (최대 N건). 이 메서드명을 searchCode의 검색어로 활용하세요.
            실패 시: Pyroscope 서버 장애이거나 해당 서비스에 profiler가 연동되지 않았을 수 있으므로
            스킵하고 다른 도구로 조사를 계속하세요.
            """)
    public String queryProfilerHotspots(
            @ToolParam(description = "조회할 서비스 이름 (예: server-a, server-b, server-c)") String service,
            @ToolParam(description = "조회 범위. 현재 시각 기준 몇 분 전까지 조회할지 (예: 10)") int minutes,
            @ToolParam(description = "반환할 핫스팟 메서드 개수 (예: 10)") int topN) {
        try {
            long until = Instant.now().toEpochMilli();
            long from = until - (long) minutes * 60 * 1000;
            String query = "process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"" + service + "\"}";

            Map<String, Object> uriVariables = Map.of(
                    "queryVal", query,
                    "fromVal", from,
                    "untilVal", until
            );

            String response = pyroscopeClient.get()
                    .uri("/pyroscope/render?query={queryVal}&from={fromVal}&until={untilVal}&format=json", uriVariables)
                    .retrieve()
                    .body(String.class);

            String summary = extractHotspots(response, topN);
            log.info("[Tool] Pyroscope 핫스팟 조회 성공: service={}", service);
            return summary;
        } catch (Exception e) {
            log.warn("[Tool] Pyroscope 핫스팟 조회 실패: {}", e.getMessage());
            return "Pyroscope 핫스팟 조회 실패 (서버 장애 또는 profiler 미연동 가능성): " + e.getMessage()
                   + " — 스킵하고 다른 도구로 조사를 계속하세요.";
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

    private String extractHotspots(String rawJson, int topN) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode flamebearer = mapper.readTree(rawJson).path("flamebearer");
            JsonNode names = flamebearer.path("names");
            JsonNode levels = flamebearer.path("levels");
            long totalTicks = flamebearer.path("numTicks").asLong(0);

            if (!names.isArray() || !levels.isArray() || totalTicks == 0) {
                return "프로파일 데이터 없음 (해당 기간 동안 수집된 샘플이 없을 수 있습니다)";
            }

            // 플레임그래프는 [offset, total, self, nameIndex] 4개 단위 그룹의 레벨 배열로 구성됨.
            // 메서드별 "자체(self) CPU 시간"을 합산해 핫스팟을 가린다 (total은 하위 호출 포함이라 중복 집계됨).
            Map<Integer, Long> selfByNameIndex = new HashMap<>();
            for (JsonNode level : levels) {
                for (int i = 0; i + 3 < level.size(); i += 4) {
                    long self = level.get(i + 2).asLong();
                    if (self > 0) {
                        int nameIndex = level.get(i + 3).asInt();
                        selfByNameIndex.merge(nameIndex, self, Long::sum);
                    }
                }
            }

            StringBuilder sb = new StringBuilder("[프로파일 핫스팟 Top " + topN + " (자체 CPU 시간 비율 기준)]\n");
            selfByNameIndex.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .limit(topN)
                    .forEach(entry -> {
                        String methodName = names.get(entry.getKey()).asText("?");
                        double percentage = entry.getValue() * 100.0 / totalTicks;
                        sb.append(String.format("%.2f%% | %s%n", percentage, methodName));
                    });
            return sb.toString().trim();
        } catch (Exception e) {
            return "핫스팟 데이터 파싱 실패: " + e.getMessage();
        }
    }
}
