package csbot.csbot.controller;

import csbot.csbot.context.CsUserContext;
import csbot.csbot.router.CsChatAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cs-chat")
@RequiredArgsConstructor
public class CsChatController {

    private final CsChatAgentService agentService;
    private final CsUserContext csUserContext;

    public record CsChatRequest(String conversationId, String message) {}
    public record CsChatResponse(String reply) {}

    @PostMapping("/messages")
    public CsChatResponse sendMessage(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody CsChatRequest request) {
        csUserContext.setLoginId(userId);
        String reply = agentService.chat(request.conversationId(), request.message());
        return new CsChatResponse(reply);
    }
}
