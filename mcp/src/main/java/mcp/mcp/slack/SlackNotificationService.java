package mcp.mcp.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackNotificationService {

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    private final @Qualifier("slackClient") RestClient slackClient;

    public void send(String report) {
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
}
