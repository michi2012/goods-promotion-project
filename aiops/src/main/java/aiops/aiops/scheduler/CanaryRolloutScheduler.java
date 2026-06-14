package aiops.aiops.scheduler;

import aiops.aiops.tools.KubernetesTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CanaryRolloutScheduler {

    private final KubernetesTools kubernetesTools;
    private final RestClient prometheusClient;
    private final ObjectMapper objectMapper;
    private final List<String> services;
    private final List<Integer> steps;
    private final int healthyChecksRequired;
    private final double maxErrorRatePercent;
    private final double minRequestRate;
    private final double maxP99LatencyMs;

    private final Map<String, CanaryState> states = new ConcurrentHashMap<>();

    record CanaryState(int lastWeight, int consecutiveHealthyCount, Integer lastProposedWeight) {}

    public CanaryRolloutScheduler(
            KubernetesTools kubernetesTools,
            @Qualifier("prometheusClient") RestClient prometheusClient,
            ObjectMapper objectMapper,
            @Value("${canary.rollout.services:server-a,server-b,server-c}") List<String> services,
            @Value("${canary.rollout.steps:10,25,50,100}") List<Integer> steps,
            @Value("${canary.rollout.healthy-checks-required:2}") int healthyChecksRequired,
            @Value("${canary.rollout.max-error-rate-percent:5.0}") double maxErrorRatePercent,
            @Value("${canary.rollout.min-request-rate:0.05}") double minRequestRate,
            @Value("${canary.rollout.max-p99-latency-ms:1000}") double maxP99LatencyMs) {
        this.kubernetesTools = kubernetesTools;
        this.prometheusClient = prometheusClient;
        this.objectMapper = objectMapper;
        this.services = services;
        this.steps = steps;
        this.healthyChecksRequired = healthyChecksRequired;
        this.maxErrorRatePercent = maxErrorRatePercent;
        this.minRequestRate = minRequestRate;
        this.maxP99LatencyMs = maxP99LatencyMs;
    }

    @Scheduled(fixedRateString = "${canary.rollout.interval-ms:300000}")
    public void checkAndPromote() {
        for (String service : services) {
            checkService(service);
        }
    }

    Map<String, CanaryState> getStates() {
        return states;
    }

    void checkService(String service) {
        int weight = kubernetesTools.getCanaryWeight(service);

        // 가중치 0/100/조회불가(-1) — 카나리 진행 중 아님 또는 조회 불가, 상태 초기화 후 스킵
        if (weight <= 0 || weight >= 100) {
            states.remove(service);
            return;
        }

        CanaryState state = states.get(service);
        if (state == null || state.lastWeight() != weight) {
            // 최초 관측 또는 외부에서 weight가 변경됨 — 상태 리셋
            state = new CanaryState(weight, 0, null);
        }

        double requestRate = queryScalar(String.format(
                "sum(rate(istio_requests_total{destination_service_name=\"%s\", destination_version=\"v2\"}[5m]))",
                service));
        if (requestRate < 0 || requestRate < minRequestRate) {
            states.put(service, state);
            return;
        }

        double errorRatePercent = queryScalar(String.format(
                "sum(rate(istio_requests_total{destination_service_name=\"%s\", destination_version=\"v2\", response_code=~\"5..\"}[5m])) "
                        + "/ sum(rate(istio_requests_total{destination_service_name=\"%s\", destination_version=\"v2\"}[5m])) * 100",
                service, service));
        if (errorRatePercent < 0) {
            states.put(service, state);
            return;
        }
        if (errorRatePercent > maxErrorRatePercent) {
            states.put(service, new CanaryState(weight, 0, state.lastProposedWeight()));
            return;
        }

        double p99LatencyMs = queryScalar(String.format(
                "histogram_quantile(0.99, sum(rate(istio_request_duration_milliseconds_bucket{destination_service_name=\"%s\", destination_version=\"v2\"}[5m])) by (le))",
                service));
        if (p99LatencyMs < 0) {
            states.put(service, state);
            return;
        }
        if (p99LatencyMs > maxP99LatencyMs) {
            states.put(service, new CanaryState(weight, 0, state.lastProposedWeight()));
            return;
        }

        int healthyCount = state.consecutiveHealthyCount() + 1;
        Integer nextWeight = nextStep(weight);

        if (nextWeight == null || healthyCount < healthyChecksRequired) {
            states.put(service, new CanaryState(weight, healthyCount, state.lastProposedWeight()));
            return;
        }

        if (nextWeight.equals(state.lastProposedWeight())) {
            // 동일 단계 재제안 방지 (쿨다운) — 승인 대기 중일 수 있음
            states.put(service, new CanaryState(weight, healthyCount, state.lastProposedWeight()));
            return;
        }

        String reason = String.format(
                "v2 정상 운영 %d회 연속 확인(요청률 %.3f req/s, 에러율 %.2f%% ≤ %.1f%%) — v2 비중 %d%% → %d%% 점진 승급 제안",
                healthyChecksRequired, requestRate, errorRatePercent, maxErrorRatePercent, weight, nextWeight);
        kubernetesTools.proposeTrafficShift(service, 100 - nextWeight, nextWeight, reason);
        log.info("[CanaryRollout] {} 점진 승급 제안: v2 {}% -> {}%", service, weight, nextWeight);
        states.put(service, new CanaryState(weight, 0, nextWeight));
    }

    private Integer nextStep(int currentWeight) {
        for (int step : steps) {
            if (step > currentWeight) {
                return step;
            }
        }
        return null;
    }

    /**
     * Prometheus 인스턴트 쿼리 결과의 스칼라 값을 조회한다.
     * 매칭되는 시계열이 없으면 0.0(트래픽 없음), 조회/파싱 자체가 실패하면 -1.0을 반환한다.
     */
    private double queryScalar(String promql) {
        try {
            Map<String, String> uriVariables = Map.of("queryVal", promql);
            String response = prometheusClient.get()
                    .uri("/api/v1/query?query={queryVal}", uriVariables)
                    .retrieve()
                    .body(String.class);
            JsonNode result = objectMapper.readTree(response).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return 0.0;
            }
            return result.get(0).path("value").get(1).asDouble(0.0);
        } catch (Exception e) {
            log.warn("[CanaryRollout] Prometheus 조회 실패: {}", e.getMessage());
            return -1.0;
        }
    }
}
