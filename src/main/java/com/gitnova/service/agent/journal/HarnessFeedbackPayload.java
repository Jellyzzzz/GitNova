package com.gitnova.service.agent.journal;

import java.util.Objects;

/** Durable Harness-owned correction or workspace-drift observation. */
public record HarnessFeedbackPayload(
        String feedbackId,
        HarnessFeedbackKind kind,
        String text
) {
    public HarnessFeedbackPayload {
        requireNonBlank(feedbackId, "feedbackId");
        Objects.requireNonNull(kind, "kind must not be null");
        requireNonBlank(text, "text");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
