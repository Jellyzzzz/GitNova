package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRuntimePolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared Frozen Execution Contract fixtures for tests. */
public final class AgentTestExecutionConfigs {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ToolSetSnapFactory SNAPSHOT_FACTORY =
            new ToolSetSnapFactory(new CanonicalJsonCodec(OBJECT_MAPPER));

    private AgentTestExecutionConfigs() {
    }

    public static AgentExecutionConfig minimal() {
        return minimal(AgentCapabilityPolicy.cloudAgent().granted());
    }

    public static AgentExecutionConfig minimal(Set<AgentCapability> capabilities) {
        return new AgentExecutionConfig(
                defaultPolicy(),
                capabilities,
                new ToolSetSnap(
                        1,
                        List.of("finishTask"),
                        "a".repeat(64)
                ),
                "1"
        );
    }

    public static AgentExecutionConfig forTools(
            List<AgentTool> tools,
            AgentRuntimePolicy policy
    ) {
        return forTools(
                tools,
                policy,
                AgentCapabilityPolicy.cloudAgent().granted()
        );
    }

    public static AgentExecutionConfig forRegistry(
            ToolRegistry registry,
            AgentRuntimePolicy policy
    ) {
        return new AgentExecutionConfig(
                policy,
                AgentCapabilityPolicy.cloudAgent().granted(),
                SNAPSHOT_FACTORY.create(registry.definitions()),
                "1"
        );
    }

    public static AgentExecutionConfig forTools(
            List<AgentTool> tools,
            AgentRuntimePolicy policy,
            Set<AgentCapability> capabilities
    ) {
        List<ToolDefinition> definitions = new ArrayList<>(tools.size());
        for (AgentTool tool : tools) {
            definitions.add(tool.definition());
        }
        return new AgentExecutionConfig(
                policy,
                capabilities,
                SNAPSHOT_FACTORY.create(definitions),
                "1"
        );
    }

    public static ToolSetResolver resolver(ToolRegistry registry) {
        return new ToolSetResolver(registry, SNAPSHOT_FACTORY);
    }

    public static AgentRuntimePolicy defaultPolicy() {
        return new AgentRuntimePolicy(
                "fake-model",
                20,
                50,
                2,
                2,
                4096,
                0.0
        );
    }
}
