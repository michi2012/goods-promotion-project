package aiops.aiops.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import aiops.aiops.approval.ActionApprovalService;
import aiops.aiops.slack.SlackNotificationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ObservabilityTools {

    private final RestClient lokiClient;
    private final RestClient tempoClient;
    private final RestClient prometheusClient;
    private final RestClient pyroscopeClient;
    private final RestClient githubClient;
    private final RestClient kafkaConnectClient;
    private final ActionApprovalService approvalService;
    private final SlackNotificationService slackService;
    private final String githubOwner;
    private final String githubRepo;

    public ObservabilityTools(
            @Qualifier("lokiClient") RestClient lokiClient,
            @Qualifier("tempoClient") RestClient tempoClient,
            @Qualifier("prometheusClient") RestClient prometheusClient,
            @Qualifier("pyroscopeClient") RestClient pyroscopeClient,
            @Qualifier("githubClient") RestClient githubClient,
            @Qualifier("kafkaConnectClient") RestClient kafkaConnectClient,
            ActionApprovalService approvalService,
            SlackNotificationService slackService,
            @Value("${github.owner}") String githubOwner,
            @Value("${github.repo}") String githubRepo) {
        this.lokiClient = lokiClient;
        this.tempoClient = tempoClient;
        this.prometheusClient = prometheusClient;
        this.pyroscopeClient = pyroscopeClient;
        this.githubClient = githubClient;
        this.kafkaConnectClient = kafkaConnectClient;
        this.approvalService = approvalService;
        this.slackService = slackService;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
    }

    @Tool(description = """
            Loki에서 특정 서비스의 로그를 조회합니다.
            언제 호출: 에러 원인 파악, 스택트레이스 확인, traceId 추출이 필요할 때.
            반환: 최근 N분간의 로그 라인 (최대 50건, 10,000자 초과 시 자동 절삭).
            실패 시: Loki 서버 장애일 수 있으므로 이 도구 호출을 스킵하고 메트릭만으로 분석을 계속하세요.
            """)
    public String queryLokiLogs(
            @ToolParam(description = "조회할 서비스 이름. Loki의 app 라벨 값과 동일하게 camelCase로 지정 (예: serverA, serverB, serverC, mcp)") String service,
            @ToolParam(description = "LogQL 필터 표현식 (예: |= \"ERROR\", 필터 없음: 빈 문자열)") String query,
            @ToolParam(description = "조회 범위. 현재 시각 기준 몇 분 전까지 조회할지 (예: 5)") int minutes) {
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
            return truncateData(response, "Loki");
        } catch (Exception e) {
            log.warn("[Tool] Loki 조회 실패: {}", e.getMessage());
            return "Loki 조회 실패 (서버 장애 가능성): " + e.getMessage() + " — 메트릭만으로 분석을 계속하세요.";
        }
    }

    @Tool(description = """
            Tempo에서 특정 트레이스 ID의 분산 추적 정보를 조회합니다.
            언제 호출: Loki 로그에서 traceId를 발견했을 때만 호출. traceId 없이 호출하지 마세요.
            반환: 해당 요청의 전체 스팬(Span) 타임라인. 어느 구간(DB, 외부 API 등)에서 지연이 발생했는지 확인 가능.
            실패 시: Tempo 서버 장애일 수 있으므로 이 도구 호출을 스킵하고 기존 정보로 분석을 계속하세요.
            """)
    public String queryTempoTrace(
            @ToolParam(description = "16자리 이상의 16진수 문자열 traceId") String traceId) {
        try {
            String response = tempoClient.get()
                                         .uri("/api/traces/{traceId}", traceId)
                                         .retrieve()
                                         .body(String.class);

            log.info("[Tool] Tempo query 성공: traceId={}", traceId);
            return truncateData(response, "Tempo");
        } catch (Exception e) {
            log.warn("[Tool] Tempo 조회 실패: {}", e.getMessage());
            return "Tempo 조회 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            Pyroscope에서 특정 서비스의 CPU 프로파일링 데이터를 조회해 핫스팟 메서드(자체 실행 시간 기준 상위 N개)를 추출합니다.
            언제 호출: Tempo 트레이스에서 특정 서비스/스팬의 지연이 확인됐지만, 그 서비스 내부의 어떤 코드(메서드)가
            병목인지 불분명할 때. "어느 서비스가 느린가"는 Tempo로, "그 서비스 안 어떤 코드가 느린가"는 이 도구로 좁히세요.
            반환: "자체 CPU 시간 비율(%) | 메서드명" 형식의 상위 핫스팟 목록 (최대 N건).
            라벨 컨벤션: Pyroscope는 service_name 라벨을 사용하며, Prometheus의 application·Loki의 app과 동일한 값(serverA/serverB/serverC)입니다.
            실패 시: Pyroscope 서버 장애이거나 해당 서비스에 profiler가 연동되지 않았을 수 있으므로
            스킵하고 Tempo·Loki 기반 분석을 계속하세요.
            """)
    public String queryProfilerHotspots(
            @ToolParam(description = "조회할 서비스 이름. Pyroscope의 service_name 라벨 값과 동일 (예: serverA, serverB, serverC)") String service,
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
                   + " — 스킵하고 Tempo·Loki 기반 분석을 계속하세요.";
        }
    }

    @Tool(description = """
            Prometheus에서 PromQL 표현식으로 메트릭을 즉시 조회합니다.
            언제 호출: 에러율, 가용성, 레이턴시, 리소스 사용률 등 수치 기반 현황 파악이 필요할 때. 분석의 첫 번째 단계로 항상 호출하세요.
            반환: 현재 시점의 메트릭 값 (JSON). 여러 지표가 필요하면 이 도구를 여러 번 호출해도 됩니다.
            실패 시: Prometheus 서버 장애이므로 로그만으로 분석을 계속하세요.
            """)
    public String queryPrometheusMetrics(
            @ToolParam(description = "조회할 PromQL 표현식. 에러율: rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]), 가용성: sum(rate(http_server_requests_seconds_count{status!~\"5..\"}[5m]))/sum(rate(http_server_requests_seconds_count[5m]))*100. 특정 서비스로 필터링할 때는 application 라벨 사용 (값: \"serverA\"/\"serverB\"/\"serverC\", job은 항상 \"promotion-api\"). 예: process_cpu_usage{application=\"serverA\", job=\"promotion-api\"}") String promql) {
        try {
            String response = callPrometheus(promql);
            log.info("[Tool] Prometheus query 성공");
            return truncateData(response, "Prometheus");
        } catch (Exception e) {
            log.warn("[Tool] Prometheus 조회 실패: {}", e.getMessage());
            return "Prometheus 조회 실패: " + e.getMessage();
        }
    }

    @Tool(description = """
            MySQL과 Redis의 데이터베이스 상태를 Prometheus 메트릭으로 한 번에 진단합니다.
            언제 호출: HikariCP 커넥션 풀 포화, 쿼리 지연, Redis 메모리·연결 이상 알람 시.
            반환: MySQL 슬로우 쿼리 비율·커넥션 수·HikariCP 대기, Redis 메모리·클라이언트 수 5개 지표 모음.
            실패 시: Prometheus 장애이므로 스킵하고 Loki 로그만으로 분석을 계속하세요.
            """)
    public String queryDatabaseHealth() {
        try {
            StringBuilder result = new StringBuilder();
            result.append("[MySQL 활성 커넥션]\n")
                  .append(truncateData(callPrometheus("mysql_global_status_threads_connected"), "MySQL-conn")).append("\n\n");
            result.append("[MySQL 슬로우 쿼리 비율 (5m)]\n")
                  .append(truncateData(callPrometheus("rate(mysql_global_status_slow_queries[5m])"), "MySQL-slow")).append("\n\n");
            result.append("[HikariCP 대기 커넥션]\n")
                  .append(truncateData(callPrometheus("hikaricp_connections_pending"), "HikariCP")).append("\n\n");
            result.append("[Redis 메모리 사용량]\n")
                  .append(truncateData(callPrometheus("redis_memory_used_bytes"), "Redis-mem")).append("\n\n");
            result.append("[Redis 연결 클라이언트 수]\n")
                  .append(truncateData(callPrometheus("redis_connected_clients"), "Redis-conn"));
            log.info("[Tool] DB 상태 진단 완료");
            return result.toString();
        } catch (Exception e) {
            log.warn("[Tool] DB 상태 진단 실패: {}", e.getMessage());
            return "DB 상태 진단 실패 (Prometheus 장애 가능성): " + e.getMessage() + " — 스킵하고 분석을 계속하세요.";
        }
    }

    @Tool(description = """
            최근 N분 이내 GitHub 레포에 머지된 커밋 목록을 조회합니다.
            언제 호출: 에러 발생 직전에 배포가 있었는지 상관관계를 확인할 때. Prometheus·Loki 분석 후 호출하세요.
            반환: "커밋 시각 | 작성자 | 커밋 메시지" 줄글 형식. AI가 분석하기 최적화된 포맷.
            실패 시: GitHub API 이상이므로 스킵하고 기존 정보로 분석을 계속하세요.
            """)
    public String queryRecentCommits(
            @ToolParam(description = "조회 범위. 현재 시각 기준 몇 분 전까지 조회할지 (예: 60)") int minutes) {
        try {
            String since = Instant.now().minusSeconds((long) minutes * 60).toString();

            String rawJson = githubClient.get()
                                         .uri("/repos/{owner}/{repo}/commits?since={since}&per_page=20",
                                                 githubOwner, githubRepo, since)
                                         .retrieve()
                                         .body(String.class);

            String summary = extractCommitSummaries(rawJson);
            log.info("[Tool] GitHub 커밋 조회 성공: {}/{}", githubOwner, githubRepo);
            return summary;
        } catch (Exception e) {
            log.warn("[Tool] GitHub 커밋 조회 실패: {}", e.getMessage());
            return "GitHub 커밋 조회 실패 — 스킵하고 기존 정보로 분석을 계속하세요: " + e.getMessage();
        }
    }

    @Tool(description = """
            Kafka 컨슈머 그룹의 consumer lag를 Prometheus에서 조회합니다.
            언제 호출: KafkaConsumerLagHigh 알람 수신 시, HPA 조정 전 lag 수치 근거 확인 시.
            반환: consumergroup·topic별 현재 lag 수치 (JSON). lag 500 이상이면 HPA 조정 검토 대상.
            실패 시: Prometheus 장애이므로 스킵하고 Loki 로그만으로 분석을 계속하세요.
            """)
    public String queryKafkaLag() {
        try {
            String response = callPrometheus("kafka_consumergroup_lag");
            log.info("[Tool] Kafka consumer lag 조회 성공");
            return truncateData(response, "KafkaLag");
        } catch (Exception e) {
            log.warn("[Tool] Kafka consumer lag 조회 실패: {}", e.getMessage());
            return "Kafka consumer lag 조회 실패 (Prometheus 장애 가능성): " + e.getMessage() + " — 스킵하고 분석을 계속하세요.";
        }
    }

    @Tool(description = """
            Debezium(Kafka Connect) 커넥터의 상태와 태스크별 실행 상태를 조회합니다. (읽기 전용)
            언제 호출: CDC 동기화 지연이 의심될 때 — DB 부하 증가와 함께 Kafka 메시지 유입이 멈췄거나
            outbox 토픽 consumer lag가 비정상적으로 누적되는 경우.
            반환: 커넥터별 전체 상태(RUNNING/FAILED/PAUSED)와 태스크별 상태·실패 원인(trace) 요약.
            실패 시: Kafka Connect 장애이므로 스킵하고 Kafka lag·로그만으로 분석을 계속하세요.
            """)
    public String queryDebeziumConnectorStatus() {
        try {
            String connectorsJson = kafkaConnectClient.get()
                                                       .uri("/connectors")
                                                       .retrieve()
                                                       .body(String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode connectors = mapper.readTree(connectorsJson);
            if (!connectors.isArray() || connectors.isEmpty()) {
                return "등록된 Debezium 커넥터가 없습니다.";
            }

            StringBuilder result = new StringBuilder();
            for (JsonNode connectorNameNode : connectors) {
                String name = connectorNameNode.asText();
                String statusJson = kafkaConnectClient.get()
                                                       .uri("/connectors/{name}/status", name)
                                                       .retrieve()
                                                       .body(String.class);
                result.append(summarizeConnectorStatus(name, statusJson)).append("\n\n");
            }
            log.info("[Tool] Debezium 커넥터 상태 조회 성공: {}개", connectors.size());
            return result.toString().trim();
        } catch (Exception e) {
            log.warn("[Tool] Debezium 커넥터 상태 조회 실패: {}", e.getMessage());
            return "Debezium 커넥터 상태 조회 실패 (Kafka Connect 장애 가능성): " + e.getMessage() + " — 스킵하고 분석을 계속하세요.";
        }
    }

    @Tool(description = """
            CDC 동기화 지연의 원인이 커넥터·태스크 실패로 확인된 경우에만 호출. Debezium 커넥터 재시작을 Slack에 승인 요청합니다.
            언제 호출: queryDebeziumConnectorStatus 결과 커넥터 또는 태스크 상태가 FAILED인 경우.
            효과: 실패한 태스크만 마지막 커밋 offset(binlog position)부터 재개 — 데이터 유실·중복 없이 복구.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeDebeziumConnectorRestart(
            @ToolParam(description = "재시작할 커넥터 이름 (queryDebeziumConnectorStatus 결과의 커넥터명)") String connectorName,
            @ToolParam(description = "재시작이 필요한 이유. 실패한 태스크명·에러 내용을 포함한 1문장.") String reason) {
        String params = String.format("{\"connector\":\"%s\"}", connectorName);
        String id = approvalService.propose("DEBEZIUM_CONNECTOR_RESTART", params, reason);

        slackService.sendBlockKit(
                "Debezium 커넥터 재시작 승인 요청: " + connectorName,
                buildDebeziumRestartBlocks(id, connectorName, reason));

        log.info("[Tool] Debezium 커넥터 재시작 제안 등록 및 Slack 발송: id={}, connector={}", id, connectorName);
        return "Debezium 커넥터 재시작 제안 등록됨 [" + id + "]: " + connectorName + "\n사유: " + reason;
    }

    @Tool(description = """
            데이터로 뒷받침된 원인이 특정된 경우에만 호출. 엔지니어 승인이 필요한 조치를 제안하고 승인 대기열에 등록합니다.
            언제 호출: 확실한 원인이 밝혀졌고 실행 가능한 구체적 조치가 있을 때만. 추측 기반 제안 금지.
            반환: 승인 명령어 (curl). 이 명령어를 보고서 권장 조치 섹션에 포함해 엔지니어가 직접 실행하도록 안내하세요.
            """)
    public String proposeAction(
            @ToolParam(description = "조치 유형 (예: KAFKA_CONSUMER_RESET, POD_RESTART, CACHE_FLUSH)") String actionType,
            @ToolParam(description = "조치 파라미터 JSON 문자열 (예: {\"topic\": \"payment\", \"group\": \"server-c\"})") String params,
            @ToolParam(description = "이 조치가 필요한 이유. 데이터 근거를 포함한 1문장.") String reason) {
        String id = approvalService.propose(actionType, params, reason);
        log.info("[Tool] 조치 제안 등록: id={}, type={}", id, actionType);
        return "조치 제안 등록됨 [" + id + "]: " + actionType + "\n" +
               "사유: " + reason + "\n" +
               "승인 명령어: curl -X POST http://aiops:8085/action/approve/" + id;
    }

    private String callPrometheus(String promql) {
        Map<String, String> uriVariables = Map.of("queryVal", promql);
        return prometheusClient.get()
                               .uri("/api/v1/query?query={queryVal}", uriVariables)
                               .retrieve()
                               .body(String.class);
    }

    private String extractCommitSummaries(String rawJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode commits = mapper.readTree(rawJson);
            if (!commits.isArray() || commits.isEmpty()) {
                return "최근 " + "커밋 없음";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode commit : commits) {
                String date = commit.path("commit").path("author").path("date").asText("?");
                String login = commit.path("author").path("login").asText(
                        commit.path("commit").path("author").path("name").asText("?"));
                String message = commit.path("commit").path("message").asText("?");
                // 커밋 메시지 첫 줄만 사용 (본문 제거)
                String firstLine = message.contains("\n") ? message.substring(0, message.indexOf('\n')) : message;
                sb.append(date).append(" | ").append(login).append(" | ").append(firstLine).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "커밋 파싱 실패: " + e.getMessage();
        }
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

    private String truncateData(String data, String source) {
        if (data == null || data.isBlank()) {
            return "응답 데이터 없음";
        }
        int maxLength = 10000;
        if (data.length() > maxLength) {
            log.warn("[Tool] {} 응답 데이터가 너무 길어 {}자로 절삭됩니다.", source, maxLength);
            return data.substring(0, maxLength) + "\n...[데이터가 너무 길어 시스템 보호를 위해 절삭되었습니다]...";
        }
        return data;
    }

    private String summarizeConnectorStatus(String connectorName, String statusJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode status = mapper.readTree(statusJson);
            String connectorState = status.path("connector").path("state").asText("UNKNOWN");
            StringBuilder sb = new StringBuilder();
            sb.append("[커넥터: ").append(connectorName).append("] 상태=").append(connectorState);

            for (JsonNode task : status.path("tasks")) {
                String taskState = task.path("state").asText("UNKNOWN");
                int taskId = task.path("id").asInt();
                sb.append("\n  - task[").append(taskId).append("] 상태=").append(taskState);
                if ("FAILED".equals(taskState)) {
                    String trace = task.path("trace").asText("");
                    String firstLine = trace.isBlank() ? "원인 정보 없음" : trace.lines().findFirst().orElse(trace);
                    sb.append(" 원인=").append(firstLine);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "[커넥터: " + connectorName + "] 상태 파싱 실패: " + e.getMessage();
        }
    }

    private List<Map<String, Object>> buildDebeziumRestartBlocks(String id, String connectorName, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🔄 Debezium 커넥터 재시작 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 커넥터:* `%s`\n*이유:* %s", connectorName, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_debezium_restart",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_debezium_restart",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }
}
