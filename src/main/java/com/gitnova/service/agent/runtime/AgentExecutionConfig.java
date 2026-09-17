package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.ContextBudget;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable, validated execution policy attached to a Run. */
public record AgentExecutionConfig(
        AgentRuntimePolicy policy,
        Set<AgentCapability> capabilities,
        ToolSetSnap toolSet,
        String contextPolicyVersion,
        // null only for the legacy contract: keep full observations, do not enable externalization.
        ObservationPolicy observationPolicy,
        // null only for persisted legacy contracts, never replaced with current application defaults.
        ContextBudget contextBudget
) {
    public AgentExecutionConfig(AgentRuntimePolicy policy, Set<AgentCapability> capabilities,
                                ToolSetSnap toolSet, String contextPolicyVersion) {
        this(policy, capabilities, toolSet, contextPolicyVersion, null, null);
    }

    public AgentExecutionConfig(AgentRuntimePolicy policy, Set<AgentCapability> capabilities,
                                ToolSetSnap toolSet, String contextPolicyVersion, ObservationPolicy observationPolicy) {
        this(policy, capabilities, toolSet, contextPolicyVersion, observationPolicy, null);
    }

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
        if (contextBudget != null) {
            Objects.requireNonNull(observationPolicy, "Context requires an explicit observation policy");
            Objects.requireNonNull(policy.maxOutputTokens(), "Context requires an explicit output reserve");
            contextBudget.assess(0, 0, policy.maxOutputTokens());
        }
    }

    public AgentCapabilityPolicy capabilityPolicy() {
        return new AgentCapabilityPolicy(capabilities);
    }
}
