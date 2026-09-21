package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.model.ModelThinking;

import java.util.Objects;

public record AgentRuntimePolicy(String model,
                                 int maxModelCalls,
                                 int maxToolCalls,
                                 int maxProtocolCorrections,
                                 int maxFinalDraftCorrections,
                                 Integer maxOutputTokens,
                                 Double temperature,
                                 ModelThinking thinking,
                                 ModelThinking summaryThinking) {
    public AgentRuntimePolicy(String model, int maxModelCalls, int maxToolCalls,
                              int maxProtocolCorrections, int maxFinalDraftCorrections,
                              Integer maxOutputTokens, Double temperature, ModelThinking thinking) {
        this(model, maxModelCalls, maxToolCalls, maxProtocolCorrections, maxFinalDraftCorrections,
                maxOutputTokens, temperature, thinking, null);
    }
    /** Legacy contracts did not persist thinking controls. Do not inject live configuration. */
    public AgentRuntimePolicy(String model, int maxModelCalls, int maxToolCalls,
                              int maxProtocolCorrections, int maxFinalDraftCorrections,
                              Integer maxOutputTokens, Double temperature) {
        this(model, maxModelCalls, maxToolCalls, maxProtocolCorrections, maxFinalDraftCorrections,
                maxOutputTokens, temperature, null);
    }

    public AgentRuntimePolicy {
        Objects.requireNonNull(model, "model must not be null");
        if (summaryThinking != null && thinking == null) {
            throw new IllegalArgumentException("summaryThinking requires explicit main thinking controls");
        }

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
