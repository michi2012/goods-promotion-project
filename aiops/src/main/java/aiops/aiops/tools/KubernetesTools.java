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
            반환: Node 목록 (이름, Ready 여부, 역할, 버전), Pod 목록 (이름, 상태, 재시작 횟수), Deployment 목록 (원하는/준비된 replica), HPA 목록 (MINPODS/MAXPODS/REPLICAS).
            실패 시: kubectl 실행 불가 환경이므로 스킵하고 메트릭만으로 분석을 계속하세요.
            """)
    public String getClusterStatus() {
        try {
            String nodes = runKubectl("get", "nodes", "--no-headers");
            String pods = runKubectl("get", "pods", "-n", namespace, "--no-headers");
            String deployments = runKubectl("get", "deployments", "-n", namespace, "--no-headers");
            String hpa = runKubectl("get", "hpa", "-n", namespace, "--no-headers");
            log.info("[K8s] 클러스터 상태 조회 완료: namespace={}", namespace);
            return "[Nodes]\n" + nodes + "\n\n[Pods]\n" + pods + "\n\n[Deployments]\n" + deployments + "\n\n[HPA]\n" + hpa;
        } catch (Exception e) {
            log.warn("[K8s] 클러스터 상태 조회 실패: {}", e.getMessage());
            return "K8s 클러스터 조회 실패 — kubectl 접근 불가 환경일 수 있습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            특정 Pod의 최근 로그를 조회합니다. (읽기 전용)
            언제 호출: 에러 원인 파악을 위해 특정 Pod의 로그를 직접 확인해야 할 때. 정확한 podName은 getClusterStatus 결과의 Pod 목록을 참고하세요.
            반환: 해당 Pod의 최근 로그 (지정한 줄 수만큼).
            실패 시: kubectl 접근 불가 환경이거나 Pod이 존재하지 않을 수 있으므로 스킵하고 다른 도구로 분석을 계속하세요.
            """)
    public String getPodLogs(
            @ToolParam(description = "조회할 Pod 이름 (getClusterStatus 결과의 Pod 목록 참고)") String podName,
            @ToolParam(description = "조회할 최근 로그 줄 수 (예: 100)") int tailLines) {
        try {
            String logs = runKubectl("logs", podName, "-n", namespace, "--tail=" + tailLines);
            log.info("[K8s] Pod 로그 조회 완료: pod={}, tailLines={}", podName, tailLines);
            return logs;
        } catch (Exception e) {
            log.warn("[K8s] Pod 로그 조회 실패: pod={}, error={}", podName, e.getMessage());
            return "Pod 로그 조회 실패 — kubectl 접근 불가 환경이거나 Pod이 존재하지 않을 수 있습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            Deployment의 현재 롤아웃 진행 상태를 조회합니다. (읽기 전용)
            언제 호출: 배포(helm upgrade, rollout restart) 이후 롤아웃이 정상적으로 완료되었는지 확인할 때.
            반환: 롤아웃 진행 상태 (예: "Waiting for deployment ... rollout to finish" 또는 "successfully rolled out").
            실패 시: kubectl 접근 불가 환경이거나 Deployment가 존재하지 않을 수 있으므로 스킵하고 다른 도구로 분석을 계속하세요.
            """)
    public String getRolloutStatus(
            @ToolParam(description = "조회할 Deployment 이름 (예: server-a, server-b, server-c)") String deploymentName) {
        try {
            String status = runKubectl("rollout", "status", "deployment/" + deploymentName, "-n", namespace);
            log.info("[K8s] 롤아웃 상태 조회 완료: deployment={}", deploymentName);
            return status;
        } catch (Exception e) {
            log.warn("[K8s] 롤아웃 상태 조회 실패: deployment={}, error={}", deploymentName, e.getMessage());
            return "롤아웃 상태 조회 실패 — kubectl 접근 불가 환경이거나 Deployment가 존재하지 않을 수 있습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            Deployment의 롤아웃 이력(revision 목록)을 조회합니다. (읽기 전용)
            언제 호출: 이전 배포 버전으로의 롤백을 검토하기 전, 사용 가능한 revision 이력을 확인할 때.
            반환: revision 번호와 변경 원인(CHANGE-CAUSE) 목록.
            실패 시: kubectl 접근 불가 환경이거나 Deployment가 존재하지 않을 수 있으므로 스킵하고 다른 도구로 분석을 계속하세요.
            """)
    public String getRolloutHistory(
            @ToolParam(description = "조회할 Deployment 이름 (예: server-a, server-b, server-c)") String deploymentName) {
        try {
            String history = runKubectl("rollout", "history", "deployment/" + deploymentName, "-n", namespace);
            log.info("[K8s] 롤아웃 이력 조회 완료: deployment={}", deploymentName);
            return history;
        } catch (Exception e) {
            log.warn("[K8s] 롤아웃 이력 조회 실패: deployment={}, error={}", deploymentName, e.getMessage());
            return "롤아웃 이력 조회 실패 — kubectl 접근 불가 환경이거나 Deployment가 존재하지 않을 수 있습니다: " + e.getMessage();
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

        String threadDumpSummary = extractThreadDumpSummary(deploymentName);

        slackService.sendBlockKit(
                "K8s 롤링 재시작 승인 요청: " + deploymentName,
                buildRestartBlocks(id, deploymentName, reason, threadDumpSummary));

        log.info("[K8s] 롤링 재시작 제안 등록 및 Slack 발송: id={}, deployment={}", id, deploymentName);
        return "롤링 재시작 제안 등록됨 [" + id + "]: " + deploymentName + "\n사유: " + reason;
    }

    @Tool(description = """
            특정 Pod 재시작(kubectl delete pod, ReplicaSet이 자동 재생성)을 Slack에 승인 요청합니다.
            언제 호출: 특정 Pod만 OOM, 높은 에러율, 응답 불능 등의 문제를 보이고 다른 Pod은 정상인 경우.
            Deployment 전체에 영향이 있다면 proposeRolloutRestart를 사용하세요.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            주의: ReplicaSet이 즉시 새 Pod을 재생성하나, 해당 Pod에서 처리 중이던 요청은 중단됨. 근거 없이 호출 금지.
            """)
    public String proposePodRestart(
            @ToolParam(description = "재시작할 Pod의 정확한 이름 (선택, getClusterStatus 결과 참고). 비워두면 deploymentName 기준 app 라벨로 첫 번째 매칭 Pod을 찾는다.") String podName,
            @ToolParam(description = "대상 Deployment 이름 (예: server-a, server-b, server-c). podName이 비어있을 때 app=<deploymentName> 라벨로 Pod을 조회하는 데 사용된다.") String deploymentName,
            @ToolParam(description = "재시작이 필요한 이유. 어떤 Pod에서 어떤 문제가 관측됐는지 포함한 1문장.") String reason) {
        String resolvedPodName;
        String resolutionNote;
        if (podName != null && !podName.isBlank()) {
            resolvedPodName = podName.trim();
            resolutionNote = "지정된 Pod";
        } else {
            try {
                String found = runKubectl("get", "pods", "-n", namespace,
                        "-l", "app=" + deploymentName,
                        "-o", "jsonpath={.items[0].metadata.name}").trim();
                if (found.isBlank() || found.equals("(결과 없음)")) {
                    return "Pod 재시작 제안 실패: app=" + deploymentName + " 라벨과 매칭되는 Pod을 찾을 수 없습니다.";
                }
                resolvedPodName = found;
                resolutionNote = "app=" + deploymentName + " 매칭 Pod 중 첫 번째";
            } catch (Exception e) {
                log.warn("[K8s] Pod 재시작 대상 조회 실패: deployment={}, error={}", deploymentName, e.getMessage());
                return "Pod 재시작 제안 실패: 대상 Pod 조회 중 오류가 발생했습니다: " + e.getMessage();
            }
        }

        String params = String.format(
                "{\"podName\":\"%s\",\"namespace\":\"%s\"}", resolvedPodName, namespace);
        String id = approvalService.propose("POD_RESTART", params, reason);

        slackService.sendBlockKit(
                "Pod 재시작 승인 요청: " + resolvedPodName,
                buildPodRestartBlocks(id, resolvedPodName, resolutionNote, reason));

        log.info("[K8s] Pod 재시작 제안 등록 및 Slack 발송: id={}, pod={}", id, resolvedPodName);
        return "Pod 재시작 제안 등록됨 [" + id + "]: " + resolvedPodName + " (" + resolutionNote + ")\n사유: " + reason;
    }

    @Tool(description = """
            HPA의 maxReplicas를 조정하는 패치를 Slack에 승인 요청합니다.
            언제 호출: 다음 중 하나라도 해당하면 즉시 호출하라.
              1) KubeHPAAtMaxReplicas 알람 — HPA가 이미 maxReplicas에 도달한 경우
              2) KafkaConsumerLagHigh 알람 — 컨슈머 서비스의 처리 용량 확대가 필요한 경우
              3) getClusterStatus에서 HPA REPLICAS == MAXPODS이고 부하가 지속되는 경우
            중요: kubectl scale 대신 반드시 이 도구를 사용하라. HPA 환경에서 kubectl scale은 HPA가 오버라이드한다.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeHpaPatch(
            @ToolParam(description = "패치할 HPA 이름 (예: server-a, server-b, server-c, gateway)") String hpaName,
            @ToolParam(description = "새로운 maxReplicas 값 (현재보다 크게, 1~10 범위)") int newMaxReplicas,
            @ToolParam(description = "패치가 필요한 이유. 메트릭 수치나 알람 내용을 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"hpa\":\"%s\",\"maxReplicas\":%d,\"namespace\":\"%s\"}",
                hpaName, newMaxReplicas, namespace);
        String id = approvalService.propose("HPA_PATCH", params, reason);

        slackService.sendBlockKit(
                "HPA maxReplicas 패치 승인 요청: " + hpaName,
                buildHpaPatchBlocks(id, hpaName, newMaxReplicas, reason));

        log.info("[K8s] HPA 패치 제안 등록 및 Slack 발송: id={}, hpa={}, maxReplicas={}", id, hpaName, newMaxReplicas);
        return "HPA 패치 제안 등록됨 [" + id + "]: " + hpaName + " → maxReplicas=" + newMaxReplicas + "\n사유: " + reason;
    }

    @Tool(description = """
            HPA의 minReplicas를 조정하는 패치를 Slack에 승인 요청합니다.
            언제 호출: 트래픽 급증이 예고되어(이벤트, 프로모션 등) 최소 replica를 선제적으로 상향해 스케일아웃 지연을 줄이고 싶을 때.
            maxReplicas 조정이 필요하면 proposeHpaPatch를 사용하세요 (이 도구는 minReplicas 전용).
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeHpaMinReplicasPatch(
            @ToolParam(description = "패치할 HPA 이름 (예: server-a, server-b, server-c, gateway)") String hpaName,
            @ToolParam(description = "새로운 minReplicas 값 (현재 maxReplicas 이하, 1 이상)") int newMinReplicas,
            @ToolParam(description = "패치가 필요한 이유. 트래픽 급증 예고 근거를 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"hpa\":\"%s\",\"minReplicas\":%d,\"namespace\":\"%s\"}",
                hpaName, newMinReplicas, namespace);
        String id = approvalService.propose("HPA_MIN_PATCH", params, reason);

        slackService.sendBlockKit(
                "HPA minReplicas 패치 승인 요청: " + hpaName,
                buildHpaMinReplicasPatchBlocks(id, hpaName, newMinReplicas, reason));

        log.info("[K8s] HPA minReplicas 패치 제안 등록 및 Slack 발송: id={}, hpa={}, minReplicas={}", id, hpaName, newMinReplicas);
        return "HPA minReplicas 패치 제안 등록됨 [" + id + "]: " + hpaName + " → minReplicas=" + newMinReplicas + "\n사유: " + reason;
    }

    @Tool(description = """
            배포 후 에러율 급증 시 Helm 롤백을 Slack에 승인 요청합니다.
            언제 호출: 다음 조건을 모두 만족하면 즉시 호출하라.
              1) 최근 배포(helm upgrade)가 있었고
              2) 배포 이후 HTTP 5xx 에러율 또는 JVM 에러가 급증한 경우
            주의: promotion-app 전체 release를 이전 revision으로 롤백한다. 서비스 선택적 롤백 불가.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeHelmRollback(
            @ToolParam(description = "롤백할 Helm release 이름 (기본값: promotion-app)") String releaseName,
            @ToolParam(description = "롤백이 필요한 이유. 배포 시각과 에러율 수치를 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"release\":\"%s\",\"namespace\":\"%s\"}", releaseName, namespace);
        String id = approvalService.propose("HELM_ROLLBACK", params, reason);

        slackService.sendBlockKit(
                "Helm 롤백 승인 요청: " + releaseName,
                buildHelmRollbackBlocks(id, releaseName, reason));

        log.info("[K8s] Helm 롤백 제안 등록 및 Slack 발송: id={}, release={}", id, releaseName);
        return "Helm 롤백 제안 등록됨 [" + id + "]: " + releaseName + "\n사유: " + reason;
    }

    @Tool(description = """
            Istio VirtualService와 DestinationRule의 현재 설정을 조회합니다. (읽기 전용)
            언제 호출: 트래픽 시프트 제안 전 현재 v1/v2 가중치 확인, OutlierDetection 임계값 확인, 카나리 배포 현황 파악 시.
            반환: 네임스페이스 내 모든 VirtualService(v1/v2 가중치)와 DestinationRule(outlierDetection 설정) yaml.
            실패 시: kubectl 접근 불가 환경이므로 스킵하고 기존 정보로 분석을 계속하세요.
            """)
    public String getIstioMeshStatus() {
        try {
            String vs = runKubectl("get", "virtualservice", "-n", namespace, "-o", "yaml");
            String dr = runKubectl("get", "destinationrule", "-n", namespace, "-o", "yaml");
            log.info("[Istio] 메시 상태 조회 완료: namespace={}", namespace);
            return "[VirtualServices]\n" + vs + "\n\n[DestinationRules]\n" + dr;
        } catch (Exception e) {
            log.warn("[Istio] 메시 상태 조회 실패: {}", e.getMessage());
            return "Istio 메시 상태 조회 실패 — kubectl 접근 불가 환경일 수 있습니다: " + e.getMessage();
        }
    }

    /**
     * 카나리(v2) VirtualService 트래픽 가중치를 조회한다. (AI @Tool 아님 — CanaryRolloutScheduler 전용)
     * @return v2 weight (0~100), 조회/파싱 실패 시 -1
     */
    public int getCanaryWeight(String serviceName) {
        try {
            String result = runKubectl("get", "virtualservice", serviceName, "-n", namespace,
                    "-o", "jsonpath={.spec.http[0].route[?(@.destination.subset=='v2')].weight}");
            return parseCanaryWeight(result);
        } catch (Exception e) {
            log.warn("[Istio] 카나리(v2) 가중치 조회 실패: service={}, error={}", serviceName, e.getMessage());
            return -1;
        }
    }

    int parseCanaryWeight(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Tool(description = """
            Istio VirtualService의 트래픽 가중치를 조정해 특정 버전(v2)으로 가는 트래픽을 격리합니다. Slack에 승인 요청합니다.
            언제 호출: 다음 중 하나라도 해당하면 즉시 호출하라.
              1) 카나리 배포 중 v2 버전에서 에러율 급증이 관측된 경우 → v2Weight=0으로 격리
              2) 특정 버전의 응답 지연이 비정상적으로 높은 경우 → v2Weight=0으로 격리
              3) 카나리 검증 완료 후 v2 전환 승인 요청 → v1Weight=0, v2Weight=100
            주의: Helm 롤백과 달리 트래픽만 격리하며 파드는 유지됨. 원인 파악 후 복구 가능.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeTrafficShift(
            @ToolParam(description = "VirtualService 대상 서비스명 (예: server-a, server-b, server-c, gateway-service, aiops)") String serviceName,
            @ToolParam(description = "v1 버전 트래픽 가중치 (0~100)") int v1Weight,
            @ToolParam(description = "v2 버전 트래픽 가중치 (0~100, v1Weight + v2Weight = 100)") int v2Weight,
            @ToolParam(description = "트래픽 조정이 필요한 이유. 관측된 에러율 또는 지연 수치를 포함한 1문장.") String reason) {
        if (v1Weight + v2Weight != 100) {
            return String.format("트래픽 시프트 거부: v1Weight(%d) + v2Weight(%d) = %d 이어야 합니다. 합이 100이 되도록 수정 후 재호출하세요.",
                    v1Weight, v2Weight, v1Weight + v2Weight);
        }
        String params = String.format(
                "{\"service\":\"%s\",\"v1Weight\":%d,\"v2Weight\":%d,\"namespace\":\"%s\"}",
                serviceName, v1Weight, v2Weight, namespace);
        String id = approvalService.propose("TRAFFIC_SHIFT", params, reason);

        slackService.sendBlockKit(
                "Istio 트래픽 시프트 승인 요청: " + serviceName,
                buildTrafficShiftBlocks(id, serviceName, v1Weight, v2Weight, reason));

        log.info("[K8s] 트래픽 시프트 제안 등록 및 Slack 발송: id={}, service={}, v1={}%, v2={}%", id, serviceName, v1Weight, v2Weight);
        return String.format("트래픽 시프트 제안 등록됨 [%s]: %s → v1=%d%% / v2=%d%%\n사유: %s", id, serviceName, v1Weight, v2Weight, reason);
    }

    @Tool(description = """
            Istio DestinationRule의 outlier detection 임계값을 조정합니다. Slack에 승인 요청합니다.
            언제 호출: 다음 조건을 만족하면 호출하라.
              1) 특정 파드에서 간헐적 5xx가 발생하지만 전체 에러율은 낮아 트래픽 시프트까지는 불필요한 경우
              2) 현재 outlier detection 임계값이 너무 높아 불량 파드가 제거되지 않는 경우
            효과: consecutive5xxErrors 횟수 연속 발생 시 해당 파드를 로드밸런서 풀에서 자동 제거.
            반환: 승인 ID. Slack에 [승인][거절] 버튼이 발송됩니다.
            """)
    public String proposeOutlierDetectionUpdate(
            @ToolParam(description = "DestinationRule 대상 서비스명 (예: server-a, server-b, server-c, gateway-service, aiops)") String serviceName,
            @ToolParam(description = "5xx 연속 발생 허용 횟수 (기본값 5, 낮출수록 민감하게 동작, 권장 범위 3~10)") int consecutive5xxErrors,
            @ToolParam(description = "조정이 필요한 이유. 관측된 파드별 에러 패턴을 포함한 1문장.") String reason) {
        String params = String.format(
                "{\"service\":\"%s\",\"consecutive5xxErrors\":%d,\"namespace\":\"%s\"}",
                serviceName, consecutive5xxErrors, namespace);
        String id = approvalService.propose("OUTLIER_DETECTION_UPDATE", params, reason);

        slackService.sendBlockKit(
                "Outlier Detection 임계값 조정 승인 요청: " + serviceName,
                buildOutlierDetectionBlocks(id, serviceName, consecutive5xxErrors, reason));

        log.info("[K8s] Outlier Detection 업데이트 제안 등록 및 Slack 발송: id={}, service={}, consecutive5xx={}", id, serviceName, consecutive5xxErrors);
        return String.format("Outlier Detection 업데이트 제안 등록됨 [%s]: %s → consecutive5xxErrors=%d\n사유: %s", id, serviceName, consecutive5xxErrors, reason);
    }

    private List<Map<String, Object>> buildRestartBlocks(String id, String deployment, String reason, String threadDumpSummary) {
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

        Map<String, Object> threadDumpSection = new LinkedHashMap<>();
        threadDumpSection.put("type", "section");
        threadDumpSection.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*스레드 덤프 (BLOCKED 스레드):*\n```%s```", threadDumpSummary)));
        blocks.add(threadDumpSection);

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

    private String extractThreadDumpSummary(String deploymentName) {
        try {
            String podName = runKubectl("get", "pods", "-n", namespace,
                    "-l", "app=" + deploymentName,
                    "-o", "jsonpath={.items[0].metadata.name}").trim();
            if (podName.isBlank() || podName.equals("(결과 없음)")) {
                return "스레드 덤프 추출 실패: 대상 파드를 찾을 수 없음";
            }

            runKubectl("exec", podName, "-n", namespace, "--", "kill", "-3", "1");
            Thread.sleep(500);
            String logs = runKubectl("logs", podName, "-n", namespace, "--tail=500");

            return extractBlockedThreads(logs);
        } catch (Exception e) {
            log.warn("[K8s] 스레드 덤프 추출 실패: {}", e.getMessage());
            return "스레드 덤프 추출 실패: " + e.getMessage();
        }
    }

    String extractBlockedThreads(String dump) {
        String[] lines = dump.split("\n");
        List<String> blockedBlocks = new ArrayList<>();
        StringBuilder current = null;
        boolean currentBlocked = false;

        for (String line : lines) {
            if (line.startsWith("\"")) {
                if (current != null && currentBlocked) {
                    blockedBlocks.add(current.toString().stripTrailing());
                }
                current = new StringBuilder(line).append("\n");
                currentBlocked = false;
            } else if (current != null) {
                current.append(line).append("\n");
                if (line.contains("BLOCKED")) {
                    currentBlocked = true;
                }
            }
        }
        if (current != null && currentBlocked) {
            blockedBlocks.add(current.toString().stripTrailing());
        }

        if (blockedBlocks.isEmpty()) {
            return "BLOCKED 상태 스레드 없음";
        }

        String result = String.join("\n\n", blockedBlocks);
        int maxLength = 2500;
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength) + "\n... (이하 생략)";
        }
        return result;
    }

    private List<Map<String, Object>> buildPodRestartBlocks(String id, String podName, String resolutionNote, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🔄 Pod 재시작 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 Pod:* `%s` (%s)\n*이유:* %s", podName, resolutionNote, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_pod_restart",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_pod_restart",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildHpaMinReplicasPatchBlocks(String id, String hpaName, int minReplicas, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "⚡ HPA minReplicas 패치 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 HPA:* `%s`\n*새 minReplicas:* %d\n*이유:* %s", hpaName, minReplicas, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_hpa_min_patch",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_hpa_min_patch",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildHpaPatchBlocks(String id, String hpaName, int maxReplicas, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "⚡ HPA maxReplicas 패치 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 HPA:* `%s`\n*새 maxReplicas:* %d\n*이유:* %s", hpaName, maxReplicas, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_hpa_patch",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_hpa_patch",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildHelmRollbackBlocks(String id, String releaseName, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🔁 Helm 롤백 승인 요청 (전체 release)"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 Release:* `%s`\n*주의:* 전체 release 롤백 — 서비스 선택 불가\n*이유:* %s", releaseName, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_helm_rollback",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_helm_rollback",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildTrafficShiftBlocks(String id, String service, int v1Weight, int v2Weight, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🔀 Istio 트래픽 시프트 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 서비스:* `%s`\n*변경 가중치:* v1=%d%% / v2=%d%%\n*이유:* %s", service, v1Weight, v2Weight, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_traffic_shift",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_traffic_shift",
                "value", id);

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "actions");
        actions.put("elements", List.of(approveBtn, rejectBtn));
        blocks.add(actions);

        return blocks;
    }

    private List<Map<String, Object>> buildOutlierDetectionBlocks(String id, String service, int consecutive5xxErrors, String reason) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("text", Map.of("type", "plain_text", "text", "🛡️ Outlier Detection 임계값 조정 승인 요청"));
        blocks.add(header);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", Map.of("type", "mrkdwn", "text",
                String.format("*대상 서비스:* `%s`\n*새 임계값:* consecutive5xxErrors=%d\n*이유:* %s", service, consecutive5xxErrors, reason)));
        blocks.add(section);

        Map<String, Object> approveBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "✅ 승인"),
                "style", "primary",
                "action_id", "approve_outlier_update",
                "value", id);

        Map<String, Object> rejectBtn = Map.of(
                "type", "button",
                "text", Map.of("type", "plain_text", "text", "❌ 거절"),
                "style", "danger",
                "action_id", "reject_outlier_update",
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
