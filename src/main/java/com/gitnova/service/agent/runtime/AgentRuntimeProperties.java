package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.ObservationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "gitnova.agent.runtime")
public record AgentRuntimeProperties(
        String model,
        int maxModelCalls,
        int maxToolCalls,
        int maxProtocolCorrections,
        int maxFinalDraftCorrections,
        Integer maxOutputTokens,
        Double temperature,
        ObservationPolicy observation,
        ContextBudget context
) {
    public AgentRuntimeProperties{
        Objects.requireNonNull(observation,"observation must not be null");
        Objects.requireNonNull(context,"context must not be null");
    }
}
