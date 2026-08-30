package com.gitnova.service.agent.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public record AgentCapabilityPolicy(Set<AgentCapability> granted) {
    public AgentCapabilityPolicy {
        Objects.requireNonNull(granted, "granted must not be null");
        if (granted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("granted must not contain null capabilities");
        }
        granted = Set.copyOf(granted);
    }

    public boolean allows(AgentCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        return granted.contains(capability);
    }

    public boolean allowsAll(Set<AgentCapability> required) {
        Objects.requireNonNull(required, "required must not be null");
        return granted.containsAll(required);
    }

    public static AgentCapabilityPolicy cloudAgent() {
        return new AgentCapabilityPolicy(Set.of(
                AgentCapability.CODE_READ,
                AgentCapability.WORKSPACE_MUTATION,
                AgentCapability.COMMAND_EXECUTE
        ));
    }
    public AgentCapabilityPolicy restrictTo(Set<AgentCapability> requested){
        Objects.requireNonNull(requested);
        Set<AgentCapability>effective=new HashSet<>(granted);
        effective.retainAll(requested);
        return new AgentCapabilityPolicy(effective);
    }
}
