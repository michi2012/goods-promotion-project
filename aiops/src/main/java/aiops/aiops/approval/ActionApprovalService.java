package aiops.aiops.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    public String executeScale(PendingAction action) {
        try {
            JsonNode params = objectMapper.readTree(action.params());
            String deployment = params.path("deployment").asText();
            int replicas = params.path("replicas").asInt();
            String ns = params.path("namespace").asText("promotion");

            List<String> command = new ArrayList<>(
                    List.of("kubectl", "scale", "deployment", deployment,
                            "-n", ns, "--replicas=" + replicas));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("[Approval] kubectl scale 실패: exitCode={}, output={}", exitCode, output);
                return "❌ kubectl scale 실패 (exitCode=" + exitCode + "): " + output;
            }

            log.info("[Approval] kubectl scale 성공: deployment={}, replicas={}", deployment, replicas);
            return "✅ 스케일 완료: `" + deployment + "` → " + replicas + " replicas\n`" + output + "`";

        } catch (Exception e) {
            log.error("[Approval] executeScale 예외: {}", e.getMessage());
            return "❌ 스케일 실행 중 오류: " + e.getMessage();
        }
    }
}
