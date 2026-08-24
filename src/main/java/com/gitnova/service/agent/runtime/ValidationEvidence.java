package com.gitnova.service.agent.runtime;

import java.util.List;
import java.util.Objects;

/** Harness-recorded successful command evidence bound to one Workspace generation. */
public record ValidationEvidence(
        List<String> argv,
        long generation,
        int exitCode,
        long durationMillis,
        boolean stdoutTruncated,
        boolean stderrTruncated
) {
    public ValidationEvidence {
        Objects.requireNonNull(argv, "argv must not be null");
        argv = List.copyOf(argv);
        if (argv.isEmpty()
                || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank arguments");
        }
        if (generation < 0 || exitCode != 0 || durationMillis < 0) {
            throw new IllegalArgumentException(
                    "successful validation requires generation >= 0, exitCode 0, and duration >= 0"
            );
        }
    }
}
