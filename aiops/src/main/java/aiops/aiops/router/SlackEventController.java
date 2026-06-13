package aiops.aiops.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/slack")
@RequiredArgsConstructor
public class SlackEventController {

    private final RouterService routerService;
    private final ObjectMapper objectMapper;

    @PostMapping("/events")
    public ResponseEntity<String> handleEvent(@RequestBody String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText();

            if ("url_verification".equals(type)) {
                return ResponseEntity.ok(root.path("challenge").asText());
            }

            if ("event_callback".equals(type)) {
                JsonNode event = root.path("event");
                if ("app_mention".equals(event.path("type").asText())) {
                    String channel = event.path("channel").asText();
                    String threadTs = event.path("thread_ts").asText(event.path("ts").asText());
                    String text = event.path("text").asText();
                    Thread.ofVirtual().start(() -> routerService.handleAppMention(channel, threadTs, text));
                }
            }

            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("[SlackEvent] 이벤트 처리 실패: {}", e.getMessage());
            return ResponseEntity.ok("");
        }
    }
}
