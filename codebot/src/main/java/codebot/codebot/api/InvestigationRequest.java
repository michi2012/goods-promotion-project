package codebot.codebot.api;

import jakarta.validation.constraints.NotBlank;

public record InvestigationRequest(
        @NotBlank String conversationId,
        @NotBlank String message) {
}
