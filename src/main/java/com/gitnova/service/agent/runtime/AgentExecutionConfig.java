package com.gitnova.service.agent.runtime;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable, validated execution policy attached to a Run. */
public record AgentExecutionConfig(
        AgentRuntimePolicy policy,
        Set<AgentCapability> capabilities,
        ToolSetSnap toolSet,
        String contextPolicyVersion
) {
    public AgentExecutionConfig {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(toolSet, "toolSet must not be null");
        Objects.requireNonNull(
                contextPolicyVersion,
                "contextPolicyVersion must not be null"
        );
        for (AgentCapability capability : capabilities) {
            if (capability == null) {
                throw new IllegalArgumentException("capabilities must not contain null");
            }
        }
        capabilities = capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(capabilities));
        if (!capabilities.contains(AgentCapability.CODE_READ)) {
            throw new IllegalArgumentException("Agent execution requires CODE_READ");
        }
        if (contextPolicyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "contextPolicyVersion must not be blank"
            );
        }
    }

    public AgentCapabilityPolicy capabilityPolicy() {
        return new AgentCapabilityPolicy(capabilities);
    }
}
