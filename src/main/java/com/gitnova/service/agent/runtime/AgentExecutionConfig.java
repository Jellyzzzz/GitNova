package com.gitnova.service.agent.runtime;

import java.util.Set;

public record AgentExecutionConfig(Set<AgentCapability> capabilities) {
    public AgentExecutionConfig{
        capabilities = Set.copyOf(capabilities);
    }
}
