package com.gitnova.service.agent.tool;

import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves a frozen Run tool contract against the tools available in this JVM. */
@Component
public final class ToolSetResolver {
    private final ToolRegistry toolRegistry;
    private final ToolSetSnapFactory snapshotFactory;

    public ToolSetResolver(
            ToolRegistry toolRegistry,
            ToolSetSnapFactory snapshotFactory
    ) {
        this.toolRegistry = Objects.requireNonNull(
                toolRegistry,
                "toolRegistry must not be null"
        );
        this.snapshotFactory = Objects.requireNonNull(
                snapshotFactory,
                "snapshotFactory must not be null"
        );
    }

    public List<ToolDefinition> resolve(
            ToolSetSnap frozen,
            AgentCapabilityPolicy capabilities
    ) {
        Objects.requireNonNull(frozen, "frozen must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");

        List<ToolDefinition> definitions = new ArrayList<>(
                frozen.enabledDefinitionNames().size()
        );

        for (String name : frozen.enabledDefinitionNames()) {
            AgentTool tool = toolRegistry.registeredTool(name);
            if (tool == null) {
                throw new IllegalStateException(
                        "Frozen tool is not registered in the current runtime: " + name
                );
            }
            if (!capabilities.allowsAll(tool.requiredCapabilities())) {
                throw new IllegalStateException(
                        "Frozen tool is not authorized by the Run capability policy: " + name
                );
            }
            definitions.add(tool.definition());
        }

        ToolSetSnap resolved = snapshotFactory.create(definitions);
        if (resolved.schemaVersion() != frozen.schemaVersion()) {
            throw new IllegalStateException(
                    "Frozen tool-set schema version is not supported: "
                            + frozen.schemaVersion()
            );
        }
        if (!resolved.enabledDefinitionNames().equals(
                frozen.enabledDefinitionNames()
        )) {
            throw new IllegalStateException(
                    "Frozen tool names do not match the current runtime"
            );
        }
        if (!resolved.definitionDigest().equals(frozen.definitionDigest())) {
            throw new IllegalStateException(
                    "Frozen tool definitions do not match the current runtime"
            );
        }

        return List.copyOf(definitions);
    }
}
