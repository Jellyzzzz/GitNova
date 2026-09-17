package com.gitnova.service.agent.context;

import java.util.Objects;

/** Measurement provenance, not a confidence percentage or a guarantee that a request fits. */
public record TokenEstimate(long tokens, Quality quality, String source) {
    public enum Quality {
        /** Counted with a reference tokenizer, not necessarily the target model's tokenizer. */
        REFERENCE_TOKENIZER,
        /** Also includes an approximation of provider-specific request framing. */
        HEURISTIC
    }

    public TokenEstimate {
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(source, "source");
        if (tokens < 0 || source.isBlank()) {
            throw new IllegalArgumentException("Invalid token estimate");
        }
    }
}
