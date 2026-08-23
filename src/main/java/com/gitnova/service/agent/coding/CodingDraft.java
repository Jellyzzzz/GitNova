package com.gitnova.service.agent.coding;

import java.util.List;
import java.util.Objects;

/** Side-effect-free terminal draft produced by the model for server-side verification. */
public record CodingDraft(
        long expectedGeneration,
        String summary,
        List<String> changedFiles,
        List<ClaimedValidation> validations,
        List<String> risks,
        List<String> followUps
) {
    public CodingDraft {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("expectedGeneration must not be negative");
        }
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        changedFiles = copyStrings(changedFiles, "changedFiles", false);
        validations = List.copyOf(validations);
        risks = copyStrings(risks, "risks", true);
        followUps = copyStrings(followUps, "followUps", true);
    }

    private static List<String> copyStrings(
            List<String> values,
            String field,
            boolean allowEmpty
    ) {
        Objects.requireNonNull(values, field + " must not be null");
        List<String> copied = List.copyOf(values);
        if (!allowEmpty && copied.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (copied.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " must contain non-blank strings");
        }
        return copied;
    }
}
