package com.gitnova.service.agent.context;

import java.util.Objects;

/** A rolling summary whose watermark covers source history, not its own persistence event. */
public record ContextSummary(
        String summaryId,
        String sessionId,
        String parentSummaryId,
        long throughSessionSequence,
        String content
) {
    public ContextSummary {
        requireNonBlank(summaryId, "summaryId");
        requireNonBlank(sessionId, "sessionId");
        if (parentSummaryId != null) requireNonBlank(parentSummaryId, "parentSummaryId");
        if (throughSessionSequence <= 0) {
            throw new IllegalArgumentException("throughSessionSequence must be positive");
        }
        requireNonBlank(content, "content");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
