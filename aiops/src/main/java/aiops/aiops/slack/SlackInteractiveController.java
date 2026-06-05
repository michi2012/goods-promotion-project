package aiops.aiops.slack;

import aiops.aiops.approval.ActionApprovalService;
import aiops.aiops.approval.ActionApprovalService.PendingAction;
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

            if ("reject_scale".equals(actionId) || "reject_restart".equals(actionId)) {
                log.info("[Slack Interactive] 거절: approvalId={}", approvalId);
                approvalService.reject(approvalId);
                String label = "reject_scale".equals(actionId) ? "스케일" : "롤링 재시작";
                slackService.sendToResponseUrl(responseUrl, "❌ " + label + " 조치가 거절되었습니다.");
                return;
            }

            if ("approve_scale".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] 스케일 승인: id={}, params={}", pending.id(), pending.params());
                slackService.sendToResponseUrl(responseUrl, approvalService.executeScale(pending));
            }

            if ("approve_restart".equals(actionId)) {
                Optional<PendingAction> action_ = approvalService.approve(approvalId);
                if (action_.isEmpty()) {
                    slackService.sendToResponseUrl(responseUrl, "⚠️ 승인 ID가 존재하지 않거나 이미 만료되었습니다: " + approvalId);
                    return;
                }
                PendingAction pending = action_.get();
                log.info("[Slack Interactive] 롤링 재시작 승인: id={}, params={}", pending.id(), pending.params());
                slackService.sendToResponseUrl(responseUrl, approvalService.executeRolloutRestart(pending));
            }

        } catch (Exception e) {
            log.error("[Slack Interactive] 처리 실패: {}", e.getMessage());
        }
    }
}
