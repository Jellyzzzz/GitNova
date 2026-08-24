package com.gitnova.service.agent.completion;

import java.util.List;
import java.util.Objects;

/** Server decision after inspecting a model-submitted completion draft. */
public record CompletionDecision(
        boolean accepted,
        boolean correctable,
        AgentCompletionOutcome outcome,
        List<String> feedback
) {
    public CompletionDecision {
        Objects.requireNonNull(feedback, "feedback must not be null");
        feedback = List.copyOf(feedback);

        if (accepted && correctable) {
            throw new IllegalArgumentException(
                    "an accepted completion decision cannot also be correctable"
            );
        }
        if (accepted) {
            Objects.requireNonNull(outcome, "accepted decision must contain an outcome");
            if (!feedback.isEmpty()) {
                throw new IllegalArgumentException("accepted decision must not contain feedback");
            }
        } else {
            if (outcome != null) {
                throw new IllegalArgumentException("rejected decision must not contain an outcome");
            }
            if (feedback.isEmpty()
                    || feedback.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "rejected decision must contain non-blank feedback"
                );
            }
        }
    }

    public static CompletionDecision accepted(AgentCompletionOutcome outcome) {
        return new CompletionDecision(true, false, outcome, List.of());
    }

    public static CompletionDecision correctable(String feedback) {
        return new CompletionDecision(false, true, null, List.of(feedback));
    }

    public static CompletionDecision rejected(String feedback) {
        return new CompletionDecision(false, false, null, List.of(feedback));
    }
}
