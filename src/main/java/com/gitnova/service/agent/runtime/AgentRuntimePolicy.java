package com.gitnova.service.agent.runtime;

import java.util.Objects;

public record AgentRuntimePolicy(String model,
                                 int maxModelCalls,
                                 int maxToolCalls,
                                 int maxProtocolCorrections,
                                 int maxFinalDraftCorrections,
                                 Integer maxOutputTokens,
                                 Double temperature) {
    public AgentRuntimePolicy {
        Objects.requireNonNull(model, "model must not be null");

        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException(
                    "maxModelCalls must be positive"
            );
        }
        if (maxToolCalls <= 0) {
            throw new IllegalArgumentException(
                    "maxToolCalls must be positive"
            );
        }
        if (maxProtocolCorrections < 0) {
            throw new IllegalArgumentException(
                    "maxProtocolCorrections must not be negative"
            );
        }
        if (maxFinalDraftCorrections < 0) {
            throw new IllegalArgumentException(
                    "maxFinalDraftCorrections must not be negative"
            );
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be positive"
            );
        }
        if (temperature != null
                && (!Double.isFinite(temperature) || temperature < 0)) {
            throw new IllegalArgumentException(
                    "temperature must be finite and non-negative"
            );
        }
    }
}
