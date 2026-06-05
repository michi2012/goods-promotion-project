package aiops.aiops.tools;

import aiops.aiops.approval.ActionApprovalService;
import aiops.aiops.slack.SlackNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KubernetesTools {

    private final ActionApprovalService approvalService;
    private final SlackNotificationService slackService;
    private final String namespace;

    public KubernetesTools(
            ActionApprovalService approvalService,
            SlackNotificationService slackService,
            @Value("${k8s.namespace:promotion}") String namespace) {
        this.approvalService = approvalService;
        this.slackService = slackService;
        this.namespace = namespace;
    }

    @Tool(description = """
            K8s 클러스터의 Pod, Deployment, HPA 상태를 조회합니다.
            언제 호출: JVM 메모리 포화, CPU 과부하, HPA 최대 복제본 도달, Pod 재시작 알람 등 K8s 인프라 이상이 의심될 때.
            반환: Pod 목록 (이름, 상태, 재시작 횟수), Deployment 목록 (원하는/준비된 replica), HPA 목록 (MINPODS/MAXPODS/REPLICAS).
            실패 시: kubectl 실행 불가 환경이므로 스킵하고 메트릭만으로 분석을 계속하세요.
            """)
    public String getClusterStatus() {
        try {
            String pods = runKubectl("get", "pods", "-n", namespace, "--no-headers");
            String deployments = runKubectl("get", "deployments", "-n", namespace, "--no-headers");
            String hpa = runKubectl("get", "hpa", "-n", namespace, "--no-headers");
            log.info("[K8s] 클러스터 상태 조회 완료: namespace={}", namespace);
            return "[Pods]\n" + pods + "\n\n[Deployments]\n" + deployments + "\n\n[HPA]\n" + hpa;
        } catch (Exception e) {
            log.warn("[K8s] 클러스터 상태 조회 실패: {}", e.getMessage());
            return "K8s 클러스터 조회 실패 — kubectl 접근 불가 환경일 수 있습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            원인 불명의 데드락 또는 응답 불능 상태가 메트릭/로그로 확인된 경우에만 호출. Deployment 롤링 재시작을 Slack에 승인 요청합니다.
            언제 호출: HTTP 요청이 처리되지 않고 큐잉되는데 liveness probe는 정상인 경우. 데드락 의심 시.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            주의: 롤링 재시작이므로 무중단이나, 진행 중인 요청은 중단될 수 있음. 근거 없이 호출 금지.
            """)
    public String proposeRolloutRestart(
            @ToolParam(description = "재시작할 Deployment 이름 (예: server-a, server-b, server-c)") String deploymentName,
            @ToolParam(description = "재시작이 필요한 이유. 데드락 또는 응답 불능의 근거를 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"deployment\":\"%s\",\"namespace\":\"%s\"}", deploymentName, namespace);
        String id = approvalService.propose("ROLLOUT_RESTART", params, reason);

        slackService.sendBlockKit(
                "K8s 롤링 재시작 승인 요청: " + deploymentName,
                buildRestartBlocks(id, deploymentName, reason));

        log.info("[K8s] 롤링 재시작 제안 등록 및 Slack 발송: id={}, deployment={}", id, deploymentName);
        return "롤링 재시작 제안 등록됨 [" + id + "]: " + deploymentName + "\n사유: " + reason;
    }

    @Tool(description = """
            Deployment 스케일 조치를 Slack에 승인 요청합니다.
            언제 호출: 다음 중 하나라도 해당하면 즉시 호출하라.
              1) getClusterStatus에서 HPA REPLICAS < MAXPODS (HPA가 더 많은 Pod를 원하는데 실제 Pod가 부족한 상태)
              2) getClusterStatus에서 HPA REPLICAS == MAXPODS이고 알람에 CPU 포화가 언급된 경우
              3) Deployment ready replica < 요청 replica이고 트래픽 부하가 높은 경우
            중요: 위 조건 해당 시 "수동으로 kubectl scale하라"는 권장 문구 대신 이 도구를 호출하라.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeScale(
            @ToolParam(description = "스케일할 Deployment 이름 (예: server-a, server-b, server-c)") String deploymentName,
            @ToolParam(description = "목표 replica 수 (현재보다 크거나 작게 가능, 1~10 범위)") int targetReplicas,
            @ToolParam(description = "스케일이 필요한 이유. 메트릭 수치를 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"deployment\":\"%s\",\"replicas\":%d,\"namespace\":\"%s\"}",
                deploymentName, targetReplicas, namespace);
        String id = approvalService.propose("SCALE_DEPLOYMENT", params, reason);

        slackService.sendBlockKit(
                "K8s 스케일 조치 승인 요청: " + deploymentName,
                buildScaleBlocks(id, deploymentName, targetReplicas, reason));

        log.info("[K8s] 스케일 제안 등록 및 Slack 발송: id={}, deployment={}, replicas={}", id, deploymentName, targetReplicas);
        return "스케일 제안 등록됨 [" + id + "]: " + deploymentName + " → " + targetReplicas + " replicas\n사유: " + reason;
    }

    private List<Map<String, Object>> buildRestartBlocks(String id, String deployment, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🔄 K8s 롤링 재시작 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 Deployment:* `%s`\n*이유:* %s", deployment, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_restart",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_restart",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildScaleBlocks(String id, String deployment, int replicas, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "⚠️ K8s 스케일 조치 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 Deployment:* `%s`\n*목표 Replica:* %d\n*이유:* %s", deployment, replicas, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_scale",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_scale",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private String runKubectl(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("kubectl 종료 코드 " + exitCode + ": " + output);
        }
        return output.isBlank() ? "(결과 없음)" : output;
    }
}
