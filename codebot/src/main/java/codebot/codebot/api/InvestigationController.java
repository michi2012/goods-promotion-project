package codebot.codebot.api;

import codebot.codebot.agent.CodebotAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Validated
public class InvestigationController {

    private final CodebotAgentService agentService;

    @PostMapping("/investigations")
    public ResponseEntity<InvestigationResponse> investigate(@Valid @RequestBody InvestigationRequest request) {
        String result = agentService.investigate(request.conversationId(), request.message());
        return ResponseEntity.ok(new InvestigationResponse(result));
    }
}
