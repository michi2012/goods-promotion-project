package aiops.aiops.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackNotificationService {

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    private final @Qualifier("slackClient") RestClient slackClient;

    public void send(String report) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[Slack] SLACK_WEBHOOK_URL 미설정 - 발송 스킵");
            return;
        }
        try {
            slackClient.post()
                    .uri(webhookUrl)
                    .body(Map.of("text", report))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Slack] 분석 보고서 발송 완료");
        } catch (Exception e) {
            log.error("[Slack] 발송 실패: {}", e.getMessage());
        }
    }

    // Slack Interactive 콜백에서 받은 response_url로 메시지 업데이트
    public void sendToResponseUrl(String responseUrl, String text) {
        if (responseUrl == null || responseUrl.isBlank()) return;
        try {
            slackClient.post()
                    .uri(responseUrl)
                    .body(Map.of("text", text, "replace_original", true))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Slack] response_url 응답 완료");
        } catch (Exception e) {
            log.error("[Slack] response_url 발송 실패: {}", e.getMessage());
        }
    }

    public void sendBlockKit(String fallbackText, List<Map<String, Object>> blocks) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[Slack] SLACK_WEBHOOK_URL 미설정 - 발송 스킵");
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", fallbackText);
            body.put("blocks", blocks);
            slackClient.post()
                    .uri(webhookUrl)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Slack] Block Kit 메시지 발송 완료");
        } catch (Exception e) {
            log.error("[Slack] Block Kit 발송 실패: {}", e.getMessage());
        }
    }
}
