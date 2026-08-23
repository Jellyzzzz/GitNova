package com.gitnova.service.agent.coding;

import java.util.List;
import java.util.Objects;

/** Model-claimed validation evidence; the Coding verifier must recompute its truth. */
public record ClaimedValidation(
        List<String> argv,
        String result
) {
    public ClaimedValidation {
        argv = List.copyOf(argv);
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("validation argv must not be empty");
        }
        Objects.requireNonNull(result, "validation result must not be null");
        if (result.isBlank()) {
            throw new IllegalArgumentException("validation result must not be blank");
        }
    }
}
