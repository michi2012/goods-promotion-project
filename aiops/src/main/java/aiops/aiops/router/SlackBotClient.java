package aiops.aiops.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackBotClient {

    private final @Qualifier("slackBotApiClient") RestClient slackBotApiClient;
    private final ObjectMapper objectMapper;

    public void postMessage(String channel, String threadTs, String text) {
        try {
            String rawResponse = slackBotApiClient.post()
                    .uri("/chat.postMessage")
                    .body(Map.of("channel", channel, "thread_ts", threadTs, "text", text))
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(rawResponse);
            if (!result.path("ok").asBoolean(false)) {
                log.error("[SlackBotClient] chat.postMessage 실패: channel={}, error={}",
                        channel, result.path("error").asText());
            }
        } catch (Exception e) {
            log.error("[SlackBotClient] chat.postMessage 호출 중 오류: channel={}, message={}", channel, e.getMessage());
        }
    }
}
