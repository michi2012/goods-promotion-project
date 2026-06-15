package aiops.aiops.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionApprovalService {

    private static final Duration TTL = Duration.ofHours(1);
    private final ConcurrentHashMap<String, PendingAction> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final RestClient kafkaConnectClient;

    public record PendingAction(String id, String actionType, String params, String reason, Instant proposedAt) {}

    public String propose(String actionType, String params, String reason) {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().proposedAt().plus(TTL)));

        String id = UUID.randomUUID().toString();
        pending.put(id, new PendingAction(id, actionType, params, reason, now));
        log.info("[Approval] 조치 제안 등록: id={}, type={}", id, actionType);
        return id;
    }

    public Optional<PendingAction> approve(String id) {
        PendingAction action = pending.remove(id);
        if (action == null) {
            log.warn("[Approval] 존재하지 않거나 만료된 승인 ID: {}", id);
            return Optional.empty();
        }
        log.info("[Approval] 승인 완료: id={}, type={}, reason={}", action.id(), action.actionType(), action.reason());
        return Optional.of(action);
    }

    public void reject(String id) {
        PendingAction removed = pending.remove(id);
        if (removed != null) {
            log.info("[Approval] 거절 처리: id={}, type={}", removed.id(), removed.actionType());
        }
    }

    public String executeRolloutRestart(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String deployment = params.path("deployment").asText();
            String ns = params.path("namespace").asText("promotion");

            List<String> command = new ArrayList<>(
                    List.of("kubectl", "rollout", "restart", "deployment/" + deployment, "-n", ns));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] kubectl rollout restart 실패: exitCode={}, output={}", exitCode, output);
                return "❌ rollout restart 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] rollout restart 성공: deployment={}", deployment);
            return "✅ 롤링 재시작 완료: `" + deployment + "`\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executeRolloutRestart 예외: {}", e.getMessage());
            return "❌ 롤링 재시작 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeHpaPatch(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String hpaName = params.path("hpa").asText();
            int maxReplicas = params.path("maxReplicas").asInt();
            String ns = params.path("namespace").asText("promotion");

            String patch = String.format("{\"spec\":{\"maxReplicas\":%d}}", maxReplicas);
            List<String> command = new ArrayList<>(
                    List.of("kubectl", "patch", "hpa", hpaName, "-n", ns, "--patch", patch, "--type", "merge"));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] kubectl patch hpa 실패: exitCode={}, output={}", exitCode, output);
                return "❌ HPA 패치 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] HPA 패치 성공: hpa={}, maxReplicas={}", hpaName, maxReplicas);
            return "✅ HPA 패치 완료: `" + hpaName + "` maxReplicas=" + maxReplicas + "\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executeHpaPatch 예외: {}", e.getMessage());
            return "❌ HPA 패치 실행 중 오류: " + e.getMessage();
        }
    }

    public String executePodRestart(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String podName = params.path("podName").asText();
            String ns = params.path("namespace").asText("promotion");

            List<String> command = new ArrayList<>(
                    List.of("kubectl", "delete", "pod", podName, "-n", ns));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] kubectl delete pod 실패: exitCode={}, output={}", exitCode, output);
                return "❌ Pod 재시작 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] Pod 재시작 성공: pod={}", podName);
            return "✅ Pod 재시작 완료: `" + podName + "`\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executePodRestart 예외: {}", e.getMessage());
            return "❌ Pod 재시작 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeHpaMinReplicasPatch(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String hpaName = params.path("hpa").asText();
            int minReplicas = params.path("minReplicas").asInt();
            String ns = params.path("namespace").asText("promotion");

            String patch = String.format("{\"spec\":{\"minReplicas\":%d}}", minReplicas);
            List<String> command = new ArrayList<>(
                    List.of("kubectl", "patch", "hpa", hpaName, "-n", ns, "--patch", patch, "--type", "merge"));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] kubectl patch hpa(minReplicas) 실패: exitCode={}, output={}", exitCode, output);
                return "❌ HPA minReplicas 패치 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] HPA minReplicas 패치 성공: hpa={}, minReplicas={}", hpaName, minReplicas);
            return "✅ HPA minReplicas 패치 완료: `" + hpaName + "` minReplicas=" + minReplicas + "\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executeHpaMinReplicasPatch 예외: {}", e.getMessage());
            return "❌ HPA minReplicas 패치 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeHelmRollback(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String release = params.path("release").asText();
            String ns = params.path("namespace").asText("promotion");

            List<String> command = new ArrayList<>(
                    List.of("helm", "rollback", release, "-n", ns));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] helm rollback 실패: exitCode={}, output={}", exitCode, output);
                return "❌ Helm 롤백 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] helm rollback 성공: release={}", release);
            return "✅ Helm 롤백 완료: `" + release + "`\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executeHelmRollback 예외: {}", e.getMessage());
            return "❌ Helm 롤백 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeTrafficShift(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String service = params.path("service").asText();
            int v1Weight = params.path("v1Weight").asInt();
            int v2Weight = params.path("v2Weight").asInt();
            String ns = params.path("namespace").asText("promotion");

            String patch = String.format(
                    "{\"spec\":{\"http\":[{\"route\":[{\"destination\":{\"host\":\"%s\",\"subset\":\"v1\"},\"weight\":%d},{\"destination\":{\"host\":\"%s\",\"subset\":\"v2\"},\"weight\":%d}]}]}}",
                    service, v1Weight, service, v2Weight);

            Path patchFile = Files.createTempFile("vs-patch-", ".json");
            String output;
            try {
                Files.writeString(patchFile, patch);
                List<String> command = List.of("kubectl", "patch", "virtualservice", service,
                        "-n", ns, "--patch-file", patchFile.toString(), "--type", "merge");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(Collectors.joining("\n"));
                }
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    log.error("[Approval] kubectl patch virtualservice 실패: exitCode={}, output={}", exitCode, output);
                    return "❌ 트래픽 시프트 실패 (exitCode=" + exitCode + "): " + output;
                }
            } finally {
                Files.deleteIfExists(patchFile);
            }

            log.info("[Approval] 트래픽 시프트 성공: service={}, v1={}%, v2={}%", service, v1Weight, v2Weight);
            return String.format("✅ 트래픽 시프트 완료: `%s` v1=%d%% / v2=%d%%\n`%s`", service, v1Weight, v2Weight, output);

        } catch (Exception e) {
            log.error("[Approval] executeTrafficShift 예외: {}", e.getMessage());
            return "❌ 트래픽 시프트 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeDebeziumConnectorRestart(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String connector = params.path("connector").asText();

            kafkaConnectClient.post()
                              .uri("/connectors/{name}/restart?includeTasks=true&onlyFailed=true", connector)
                              .retrieve()
                              .toBodilessEntity();

            log.info("[Approval] Debezium 커넥터 재시작 성공: connector={}", connector);
            return "✅ Debezium 커넥터 재시작 완료: `" + connector + "` (실패한 태스크만 재시작)";

        } catch (Exception e) {
            log.error("[Approval] executeDebeziumConnectorRestart 예외: {}", e.getMessage());
            return "❌ Debezium 커넥터 재시작 실행 중 오류: " + e.getMessage();
        }
    }

    public String executeOutlierDetectionUpdate(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String service = params.path("service").asText();
            int consecutive5xxErrors = params.path("consecutive5xxErrors").asInt();
            String ns = params.path("namespace").asText("promotion");

            String patch = String.format(
                    "{\"spec\":{\"trafficPolicy\":{\"outlierDetection\":{\"consecutive5xxErrors\":%d}}}}",
                    consecutive5xxErrors);

            Path patchFile = Files.createTempFile("dr-patch-", ".json");
            String output;
            try {
                Files.writeString(patchFile, patch);
                List<String> command = List.of("kubectl", "patch", "destinationrule", service,
                        "-n", ns, "--patch-file", patchFile.toString(), "--type", "merge");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(Collectors.joining("\n"));
                }
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    log.error("[Approval] kubectl patch destinationrule 실패: exitCode={}, output={}", exitCode, output);
                    return "❌ Outlier Detection 업데이트 실패 (exitCode=" + exitCode + "): " + output;
                }
            } finally {
                Files.deleteIfExists(patchFile);
            }

            log.info("[Approval] Outlier Detection 업데이트 성공: service={}, consecutive5xx={}", service, consecutive5xxErrors);
            return String.format("✅ Outlier Detection 업데이트 완료: `%s` consecutive5xxErrors=%d\n`%s`", service, consecutive5xxErrors, output);

        } catch (Exception e) {
            log.error("[Approval] executeOutlierDetectionUpdate 예외: {}", e.getMessage());
            return "❌ Outlier Detection 업데이트 실행 중 오류: " + e.getMessage();
        }
    }

}
