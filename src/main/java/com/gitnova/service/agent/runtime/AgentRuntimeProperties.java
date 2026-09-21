package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.model.ModelThinking;
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
        ContextBudget context,
        ModelThinking thinking,
        ModelThinking summaryThinking
) {
    public AgentRuntimeProperties{
        Objects.requireNonNull(observation,"observation must not be null");
        Objects.requireNonNull(context,"context must not be null");
        thinking = thinking == null ? ModelThinking.disabled() : thinking;
        summaryThinking = summaryThinking == null ? ModelThinking.disabled() : summaryThinking;
    }
}
