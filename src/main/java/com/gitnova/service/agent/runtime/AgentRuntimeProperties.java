package com.gitnova.service.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gitnova.agent.runtime")
public record AgentRuntimeProperties(
        String model,
        int maxModelCalls,
        int maxToolCalls,
        int maxProtocolCorrections,
        int maxFinalDraftCorrections,
        Integer maxOutputTokens,
        Double temperature
) {
}
