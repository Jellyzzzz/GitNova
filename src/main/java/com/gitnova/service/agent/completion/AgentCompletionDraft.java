package com.gitnova.service.agent.completion;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Side-effect-free completion claims produced by the model.
 *
 * <p>Every field remains untrusted until the server compares it with the current Workspace,
 * canonical diff, and recorded validation evidence.</p>
 */
public record AgentCompletionDraft(
        long expectedGeneration,
        String summary,
        List<AgentFindingDraft> findings,
        List<String> claimedChangedFiles,
        List<ValidationClaim> claimedValidations,
        List<String> risks,
        List<String> followUps
) {
    public AgentCompletionDraft {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("expectedGeneration must not be negative");
        }
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }

        findings = copyObjects(findings, "findings");
        claimedValidations = copyObjects(claimedValidations, "claimedValidations");
        claimedChangedFiles = copyClaimedPaths(claimedChangedFiles);
        risks = copyStrings(risks, "risks");
        followUps = copyStrings(followUps, "followUps");
    }

    private static <T> List<T> copyObjects(List<T> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null values");
        }
        return List.copyOf(values);
    }

    private static List<String> copyClaimedPaths(List<String> values) {
        Objects.requireNonNull(values, "claimedChangedFiles must not be null");
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (!AgentFindingDraft.isNormalizedRepositoryPath(value) || !unique.add(value)) {
                throw new IllegalArgumentException(
                        "claimedChangedFiles must contain unique normalized repository paths"
                );
            }
        }
        return List.copyOf(values);
    }

    private static List<String> copyStrings(List<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " must contain non-blank strings");
        }
        return List.copyOf(values);
    }
}
