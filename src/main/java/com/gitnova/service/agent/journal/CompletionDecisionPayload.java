package com.gitnova.service.agent.journal;

import com.gitnova.service.agent.completion.CompletionDecision;

import java.util.Objects;

/** Server verification decision for a terminal Tool Call. */
public record CompletionDecisionPayload(
        String modelCallId,
        String toolCallId,
        CompletionDecision decision
) {
    public CompletionDecisionPayload {
        requireNonBlank(modelCallId, "modelCallId");
        requireNonBlank(toolCallId, "toolCallId");
        Objects.requireNonNull(decision, "decision must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
