package aiops.aiops.slack;

import aiops.aiops.approval.ActionApprovalService;
import aiops.aiops.approval.ActionApprovalService.PendingAction;
import aiops.aiops.linear.LinearAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/slack")
@RequiredArgsConstructor
public class SlackInteractiveController {

    private final ActionApprovalService approvalService;
    private final SlackNotificationService slackService;
    private final LinearAuditService linearAuditService;
    private final ObjectMapper objectMapper;

    // Slack Interactive Components는 application/x-www-form-urlencoded + payload 필드로 JSON 전송
    @PostMapping(value = "/interactive", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> handleInteractive(@RequestParam("payload") String payloadJson) {
        // Slack 3초 타임아웃: 즉시 200 응답 후 비동기 처리
        CompletableFuture.runAsync(() -> processPayload(payloadJson));
        return ResponseEntity.ok().build();
    }

    private void processPayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            JsonNode action = payload.path("actions").path(0);
            String actionId = action.path("action_id").asText();
            String approvalId = action.path("value").asText();
            String responseUrl = payload.path("response_url").asText();

            if ("reject_restart".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ 롤링 재시작 조치가 거절되었습니다.");
                return;
            }

            if ("approve_restart".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] 롤링 재시작 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeRolloutRestart(pending));
            }

            if ("reject_pod_restart".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ Pod 재시작 조치가 거절되었습니다.");
                return;
            }

            if ("approve_pod_restart".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] Pod 재시작 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executePodRestart(pending));
            }

            if ("reject_hpa_patch".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ HPA 패치 조치가 거절되었습니다.");
                return;
            }

            if ("approve_hpa_patch".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] HPA 패치 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeHpaPatch(pending));
            }

            if ("reject_hpa_min_patch".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ HPA minReplicas 패치 조치가 거절되었습니다.");
                return;
            }

            if ("approve_hpa_min_patch".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] HPA minReplicas 패치 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeHpaMinReplicasPatch(pending));
            }

            if ("reject_helm_rollback".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ Helm 롤백 조치가 거절되었습니다.");
                return;
            }

            if ("approve_helm_rollback".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] Helm 롤백 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeHelmRollback(pending));
            }

            if ("reject_traffic_shift".equals(actionId)) {
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ 트래픽 시프트 조치가 거절되었습니다.");
                return;
            }

            if ("approve_traffic_shift".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] 트래픽 시프트 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeTrafficShift(pending));
            }

            if ("reject_outlier_update".equals(actionId)) {
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ Outlier Detection 업데이트 조치가 거절되었습니다.");
                return;
            }

            if ("approve_outlier_update".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] Outlier Detection 업데이트 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeOutlierDetectionUpdate(pending));
            }

            if ("reject_debezium_restart".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                slackService.sendToResponseUrl(responseUrl, "❌ Debezium 커넥터 재시작 조치가 거절되었습니다.");
                return;
            }

            if ("approve_debezium_restart".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] Debezium 커넥터 재시작 승인: id={}, params={}", pending.id(), pending.params());
                sendResultWithAudit(responseUrl, pending, approvalService.executeDebeziumConnectorRestart(pending));
            }

        } catch (Exception e) {
            log.error("[Slack Interactive] 처리 실패: {}", e.getMessage());
        }
    }

    // 자동조치 실행 결과와 Linear 감사 티켓 생성 결과를 하나의 메시지로 합쳐 전송한다.
    // sendToResponseUrl은 replace_original=true이므로, 두 번 호출하면 첫 메시지가 덮어써진다.
    private void sendResultWithAudit(String responseUrl, PendingAction pending, String executionResult) {
        String auditResult = linearAuditService.createAuditTicket(
                pending.actionType(), pending.params(), pending.reason(), executionResult);
        slackService.sendToResponseUrl(responseUrl, executionResult + "\n\n" + auditResult);
    }
}
