package com.gitnova.service.agent.runtime;

import java.util.Optional;
import java.util.Objects;

/**
 * Immutable trusted state exposed to completion inspection.
 * The optional successful command is advisory evidence, never a completion prerequisite.
 */
public record RunStateView(
        Optional<ValidationEvidence> latestSuccessfulValidation
) {
    public RunStateView {
        Objects.requireNonNull(
                latestSuccessfulValidation,
                "latestSuccessfulValidation must not be null"
        );
    }

    public static RunStateView empty() {
        return new RunStateView(Optional.empty());
    }
}
