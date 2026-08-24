package com.gitnova.service.agent.completion;

import com.gitnova.service.agent.context.Severity;

import java.util.Objects;

/**
 * A model-claimed, repository-bound finding submitted at task completion.
 *
 * <p>The record validates only the shape of the claim. It does not prove that the cited code or
 * explanation is correct; authoritative completion inspection owns that decision.</p>
 */
public record AgentFindingDraft(
        String filePath,
        int startLine,
        int endLine,
        Severity severity,
        String category,
        String evidence,
        String explanation,
        String suggestion,
        double confidence
) {
    public static final int MAX_PATH_CHARS = 4096;

    public AgentFindingDraft {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        category = requireNonBlank(category, "category");
        evidence = requireNonBlank(evidence, "evidence");
        explanation = requireNonBlank(explanation, "explanation");
        suggestion = requireNonBlank(suggestion, "suggestion");

        if (!isNormalizedRepositoryPath(filePath)) {
            throw new IllegalArgumentException(
                    "filePath must be a normalized repository-relative path"
            );
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("finding line range is invalid");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public static boolean isNormalizedRepositoryPath(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > MAX_PATH_CHARS
                || path.indexOf('\0') >= 0
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*")
                || path.contains("\\")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
