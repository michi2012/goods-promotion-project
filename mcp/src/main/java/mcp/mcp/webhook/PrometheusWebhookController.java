package mcp.mcp.webhook;

import lombok.RequiredArgsConstructor;
import mcp.mcp.agent.AiOpsAgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class PrometheusWebhookController {

    private final AiOpsAgentService agentService;

    @PostMapping("/prometheus")
    public ResponseEntity<Void> receiveAlert(@RequestBody String payload) {
        Thread.ofVirtual().start(() -> agentService.analyze(payload));
        return ResponseEntity.ok().build();
    }
}
