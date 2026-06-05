package aiops.aiops.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import aiops.aiops.slack.SlackNotificationService;
import aiops.aiops.tools.KubernetesTools;
import aiops.aiops.tools.ObservabilityTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOpsAgentService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObservabilityTools observabilityTools;
    private final KubernetesTools kubernetesTools;
    private final SlackNotificationService slackService;
    private final AlertDeduplicationService deduplicationService;

    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            당신은 시니어 SRE 엔지니어입니다. Prometheus 알람을 받으면 아래 절차를 순서대로 수행하세요.
            도구 호출이 실패해도 분석을 중단하지 말고 수집된 정보로 다음 단계를 계속 진행하세요.

            ## 분석 절차

            1. payload에서 alertname, severity, tier, summary, description을 파악하라.

            2. queryPrometheusMetrics를 호출하여 현재 에러율과 핵심 지표를 확인하라.
               - 에러율: rate(http_server_requests_seconds_count{status=~"5.."}[5m])
               - 가용성: sum(rate(http_server_requests_seconds_count{status!~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) * 100

            3. queryDatabaseHealth를 호출하여 MySQL·Redis 상태를 확인하라.
               HikariCP 대기 커넥션이 0보다 크거나, 슬로우 쿼리 비율이 증가 추세이거나, Redis 메모리가 높다면 DB 병목을 원인 후보로 기록하라.

            4. 대상 서비스를 식별하라:
               - payload 라벨에 'app' 또는 'job'이 있으면 그 값을 사용하라.
               - 없다면 2번 메트릭 조회 결과에서 에러가 가장 심한 서비스명을 동적으로 찾아라.

            5. 4번에서 식별한 서비스명으로 queryLokiLogs를 호출하고 최근 5분 ERROR 로그를 조회하라.

            6. 5번 로그에서 'traceId' 또는 'trace_id'가 있으면 queryTempoTrace를 호출하라. 없으면 스킵하라.

            7. queryRecentCommits(60)를 호출하여 최근 1시간 배포 이력을 조회하라.
               커밋 시각이 장애 발생 시각 10분 이내라면 해당 커밋을 원인 후보 1순위로 기록하고 롤백을 권장 조치에 포함하라.
               추가로, 배포 이력이 있고 HTTP 5xx 에러율이 급증하는 경우(SystemErrorRateCritical, HighErrorBurnRateFast 동반) proposeHelmRollback("promotion-app", ...)을 호출하라.
               단, 인프라 포화(CPU·Heap·HPA 최대 도달)가 단독 원인으로 의심되는 경우에는 호출하지 마라.

            8. severity가 critical이고 JVM Heap 포화, CPU 포화, HPA 최대 복제본 도달 알람이라면 getClusterStatus를 호출하라.
               getClusterStatus 결과 또는 알람명을 보고 아래 중 하나라도 해당하면 proposeHpaPatch를 호출하라:
               - KubeHPAAtMaxReplicas 알람 — 알람의 horizontalpodautoscaler 라벨에서 HPA 이름을 읽어 maxReplicas를 현재값 +2로 제안하라.
               - HPA REPLICAS == MAXPODS이고 CPU 포화가 알람 또는 메트릭으로 확인된 경우
               - KafkaConsumerLagHigh 알람 — 다음 순서로 처리하라:
                 1) queryPrometheusMetrics로 실제 랙 수치를 조회하라: kafka_consumergroup_lag{namespace="promotion"}
                 2) 랙이 500 이상이고 지속 증가 추세이면 consumergroup 라벨에서 서비스명(server-a, server-b, server-c)을 추론하여 해당 서비스의 HPA에 proposeHpaPatch를 호출하라.
                 3) 랙이 500 미만이거나 감소 추세이면 proposeHpaPatch를 호출하지 말고 "랙 자연 해소 중" 으로 보고서에 기록하라.
               - KubeHPAOverprovisioned 알람 — 부하가 정상화되어 HPA가 minReplicas로 30분 이상 유지 중. maxReplicas를 minReplicas + 3 값으로 원복 제안하라 (예: minReplicas=2이면 maxReplicas=5로 제안).
               중요: kubectl scale 또는 수동 스케일 권장 문구를 보고서에 쓰지 마라. HPA가 있는 서비스는 반드시 proposeHpaPatch를 사용하라.
               - HTTP 요청이 큐잉되어 처리 안 되는데 liveness probe는 정상(up==1)이고 에러 로그에 deadlock/blocked thread가 확인되면 proposeRolloutRestart를 호출하라.
               - 추측 기반 제안 절대 금지. getClusterStatus 또는 알람 수치로 근거가 없으면 호출하지 마라.

            9. 수집한 모든 정보를 바탕으로 아래 형식의 보고서를 작성하라.
               데이터로 뒷받침되지 않는 추측은 쓰지 마라.

            ## 연쇄 장애 추론 원칙
            - 원인과 증상이 여러 계층에 걸쳐 있는지 반드시 검토하라.
            - 예시: "타겟 DB 부하 증가 → CDC 추출 지연 → Kafka 프로듀서 블로킹 → API 스레드 풀 포화 → HTTP 5xx"
            - 이런 다단계 인과관계가 보이면 각 계층을 순서대로 원인 섹션에 서술하라.
            - Kafka 컨슈머 랙, CDC 지연, HikariCP 대기, Redis 지연 중 둘 이상이 동시에 높다면 연쇄 장애를 의심하라.

            ## 보고서 형식 (슬랙 문법: 굵은 글씨는 * 한 개만)
            ## 🚨 [*tier*] [*severity*] alertname
            *요약*: summary 내용 그대로
            *원인*: 수집된 메트릭·로그·트레이스·배포 이력 기반의 구체적 원인 (연쇄 장애라면 계층별 순서 서술)
            *영향 범위*: 영향받는 서비스와 기능
            *권장 조치*: 단계별 조치 1~3개 (롤백이 유력하면 첫 번째에 배치)
            *핵심 지표*: 주요 수치 요약 1~3개
            """;

    public void analyze(String alertPayload) {
        log.info("[AIOps] 알람 수신 및 분석 시작");
        String alertName = "알 수 없는 알람";
        try {
            JsonNode rootNode = objectMapper.readTree(alertPayload);
            alertName = rootNode.path("alerts").path(0).path("labels").path("alertname").asText(alertName);
            String status = rootNode.path("status").asText();

            if ("resolved".equalsIgnoreCase(status)) {
                log.info("[AIOps] 정상 회복 확인. AI 분석 스킵.");
                slackService.send("🟢 *[정상 회복]* `" + alertName + "` 장애가 성공적으로 복구되었습니다.");
                return;
            }

            String fingerprint = rootNode.path("groupLabels").toString();
            if (deduplicationService.isDuplicate(fingerprint)) {
                log.info("[AIOps] 중복 알람 억제 (30분 TTL): alertName={}", alertName);
                return;
            }

            log.info("[AIOps] Firing 상태 확인. AI 도구 호출 및 원인 분석 진행");
            String report = chatClientBuilder.build()
                                             .prompt()
                                             .system(SYSTEM_PROMPT)
                                             .user(alertPayload)
                                             .tools(observabilityTools, kubernetesTools)
                                             .call()
                                             .content();

            log.info("[AIOps] 분석 완료, Slack 발송");
            slackService.send(report);

        } catch (Exception e) {
            log.error("[AIOps] 분석 실패: {}", e.getMessage());
            slackService.send("🔴 *[AIOps 분석 실패]* `" + alertName + "` 알람 처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}