package com.gitnova.service.agent.runtime;

import java.util.Objects;
import java.util.Set;

/**
 * Trusted, Harness-recorded evidence of what a non-finalized review actually inspected.
 * It is coverage metadata, never a substitute for a verified ReviewDraft.
 */
public record ReviewCoverage(
        boolean changesListed,
        Set<String> diffedFiles,
        Set<String> readFiles
) {
    public ReviewCoverage {
        diffedFiles = immutablePaths(diffedFiles, "diffedFiles");
        readFiles = immutablePaths(readFiles, "readFiles");
    }

    public static ReviewCoverage empty() {
        return new ReviewCoverage(false, Set.of(), Set.of());
    }

    public boolean hasEvidence() {
        return changesListed || !diffedFiles.isEmpty() || !readFiles.isEmpty();
    }

    private static Set<String> immutablePaths(Set<String> paths, String field) {
        Objects.requireNonNull(paths, field + " must not be null");
        for (String path : paths) {
            Objects.requireNonNull(path, field + " must not contain null paths");
            if (path.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank paths");
            }
        }
        return Set.copyOf(paths);
    }
}
