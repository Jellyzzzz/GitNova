package com.gitnova.service.agent.completion;

import java.util.List;
import java.util.Objects;

/**
 * A validation outcome claimed by the model.
 *
 * <p>It is evidence to inspect, not an authoritative record that the command ran or passed.</p>
 */
public record ValidationClaim(
        List<String> argv,
        String result
) {
    public ValidationClaim {
        Objects.requireNonNull(argv, "argv must not be null");
        argv = List.copyOf(argv);
        if (argv.isEmpty()
                || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                    "validation argv must contain non-blank arguments"
            );
        }
        Objects.requireNonNull(result, "result must not be null");
        if (result.isBlank()) {
            throw new IllegalArgumentException("validation result must not be blank");
        }
    }
}
