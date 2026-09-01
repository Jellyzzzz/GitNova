package com.gitnova.service.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolSetResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSetSnapFactory snapshotFactory = new ToolSetSnapFactory(
            new CanonicalJsonCodec(objectMapper)
    );

    @Test
    void shouldResolveOnlyToolsNamedByTheFrozenContract() {
        AgentTool read = tool("readFile", "Read a file", AgentCapability.CODE_READ);
        AgentTool write = tool(
                "writeFile",
                "Write a file",
                AgentCapability.WORKSPACE_MUTATION
        );
        AgentTool newlyRegistered = tool(
                "searchCode",
                "Search source code",
                AgentCapability.CODE_READ
        );
        ToolSetSnap frozen = snapshotFactory.create(List.of(
                write.definition(),
                read.definition()
        ));
        ToolSetResolver resolver = resolver(read, write, newlyRegistered);

        List<ToolDefinition> resolved = resolver.resolve(
                frozen,
                policy(AgentCapability.CODE_READ, AgentCapability.WORKSPACE_MUTATION)
        );
        List<String> resolvedNames = new ArrayList<>();
        for (ToolDefinition definition : resolved) {
            resolvedNames.add(definition.name());
        }

        assertEquals(
                List.of("readFile", "writeFile"),
                resolvedNames
        );
    }

    @Test
    void shouldRejectADeletedFrozenTool() {
        AgentTool read = tool("readFile", "Read a file", AgentCapability.CODE_READ);
        AgentTool write = tool(
                "writeFile",
                "Write a file",
                AgentCapability.WORKSPACE_MUTATION
        );
        ToolSetSnap frozen = snapshotFactory.create(List.of(
                read.definition(),
                write.definition()
        ));

        assertThrows(
                IllegalStateException.class,
                () -> resolver(read).resolve(
                        frozen,
                        policy(AgentCapability.CODE_READ, AgentCapability.WORKSPACE_MUTATION)
                )
        );
    }

    @Test
    void shouldRejectAFrozenToolNoLongerAuthorizedByCapabilities() {
        AgentTool read = tool("readFile", "Read a file", AgentCapability.CODE_READ);
        AgentTool write = tool(
                "writeFile",
                "Write a file",
                AgentCapability.WORKSPACE_MUTATION
        );
        ToolSetSnap frozen = snapshotFactory.create(List.of(
                read.definition(),
                write.definition()
        ));

        assertThrows(
                IllegalStateException.class,
                () -> resolver(read, write).resolve(
                        frozen,
                        policy(AgentCapability.CODE_READ)
                )
        );
    }

    @Test
    void shouldRejectAChangedToolDefinition() {
        AgentTool frozenRead = tool(
                "readFile",
                "Read a file",
                AgentCapability.CODE_READ
        );
        ToolSetSnap frozen = snapshotFactory.create(List.of(frozenRead.definition()));
        AgentTool changedRead = tool(
                "readFile",
                "Read repository text",
                AgentCapability.CODE_READ
        );

        assertThrows(
                IllegalStateException.class,
                () -> resolver(changedRead).resolve(
                        frozen,
                        policy(AgentCapability.CODE_READ)
                )
        );
    }

    @Test
    void shouldRejectAnUnsupportedSnapshotSchemaVersion() {
        AgentTool read = tool("readFile", "Read a file", AgentCapability.CODE_READ);
        ToolSetSnap current = snapshotFactory.create(List.of(read.definition()));
        ToolSetSnap unsupported = new ToolSetSnap(
                current.schemaVersion() + 1,
                current.enabledDefinitionNames(),
                current.definitionDigest()
        );

        assertThrows(
                IllegalStateException.class,
                () -> resolver(read).resolve(
                        unsupported,
                        policy(AgentCapability.CODE_READ)
                )
        );
    }

    @Test
    void shouldRejectMissingInputs() {
        AgentTool read = tool("readFile", "Read a file", AgentCapability.CODE_READ);
        ToolSetResolver resolver = resolver(read);
        ToolSetSnap frozen = snapshotFactory.create(List.of(read.definition()));

        assertThrows(
                NullPointerException.class,
                () -> resolver.resolve(null, policy(AgentCapability.CODE_READ))
        );
        assertThrows(
                NullPointerException.class,
                () -> resolver.resolve(frozen, null)
        );
    }

    private ToolSetResolver resolver(AgentTool... tools) {
        return new ToolSetResolver(
                new ToolRegistry(List.of(tools)),
                snapshotFactory
        );
    }

    private AgentCapabilityPolicy policy(AgentCapability... capabilities) {
        return new AgentCapabilityPolicy(Set.of(capabilities));
    }

    private AgentTool tool(
            String name,
            String description,
            AgentCapability requiredCapability
    ) {
        ToolDefinition definition = new ToolDefinition(
                name,
                description,
                simpleSchema()
        );
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(
                    ToolExecutionContext execution,
                    JsonNode arguments
            ) {
                throw new UnsupportedOperationException("not used by this test");
            }

            @Override
            public Set<AgentCapability> requiredCapabilities() {
                return Set.of(requiredCapability);
            }
        };
    }

    private ObjectNode simpleSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }
}
