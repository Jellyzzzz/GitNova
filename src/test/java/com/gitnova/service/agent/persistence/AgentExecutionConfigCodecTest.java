package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRuntimePolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionConfigCodecTest {
    private static final String TOOL_DIGEST = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentExecutionConfigCodec codec = new AgentExecutionConfigCodec(
            new CanonicalJsonCodec(objectMapper),
            objectMapper
    );

    @Test
    void roundTripShouldPreserveTheCompleteFrozenContract() {
        AgentExecutionConfig original = config(
                policy(20, 50),
                new LinkedHashSet<>(List.of(
                        AgentCapability.WORKSPACE_MUTATION,
                        AgentCapability.CODE_READ,
                        AgentCapability.COMMAND_EXECUTE
                )),
                toolSet("applyPatch", "finishTask", "readFile"),
                "context-v1"
        );

        CanonicalJsonCodec.EncodedJson encoded = codec.encode(original);
        AgentExecutionConfig decoded = codec.decode(encoded.json());

        assertEquals(original, decoded);
        assertEquals(encoded, codec.encode(decoded));
    }

    @Test
    void capabilityInputOrderShouldNotChangeJsonOrDigest() {
        AgentExecutionConfig first = config(
                policy(20, 50),
                new LinkedHashSet<>(List.of(
                        AgentCapability.WORKSPACE_MUTATION,
                        AgentCapability.CODE_READ
                )),
                toolSet("finishTask", "readFile"),
                "context-v1"
        );
        AgentExecutionConfig second = config(
                policy(20, 50),
                new LinkedHashSet<>(List.of(
                        AgentCapability.CODE_READ,
                        AgentCapability.WORKSPACE_MUTATION
                )),
                toolSet("readFile", "finishTask"),
                "context-v1"
        );

        assertEquals(codec.encode(first), codec.encode(second));
    }

    @Test
    void changingPolicyShouldChangeTheCompleteContractDigest() {
        AgentExecutionConfig first = standardConfig();
        AgentExecutionConfig second = config(
                policy(21, 50),
                first.capabilities(),
                first.toolSet(),
                first.contextPolicyVersion()
        );

        assertNotEquals(
                codec.encode(first).digest(),
                codec.encode(second).digest()
        );
    }

    @Test
    void changingToolSetShouldChangeTheCompleteContractDigest() {
        AgentExecutionConfig first = standardConfig();
        AgentExecutionConfig second = config(
                first.policy(),
                first.capabilities(),
                new ToolSetSnap(1, List.of("finishTask", "readFile"), "b".repeat(64)),
                first.contextPolicyVersion()
        );

        assertNotEquals(
                codec.encode(first).digest(),
                codec.encode(second).digest()
        );
    }

    @Test
    void changingContextPolicyVersionShouldChangeTheCompleteContractDigest() {
        AgentExecutionConfig first = standardConfig();
        AgentExecutionConfig second = config(
                first.policy(),
                first.capabilities(),
                first.toolSet(),
                "context-v2"
        );

        assertNotEquals(
                codec.encode(first).digest(),
                codec.encode(second).digest()
        );
    }

    @Test
    void unsupportedWireSchemaVersionShouldBeRejected() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(
                codec.encode(standardConfig()).json()
        );
        root.put("schemaVersion", 2);
        String unsupported = objectMapper.writeValueAsString(root);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                codec.decode(unsupported);
            }
        });
    }

    private AgentExecutionConfig standardConfig() {
        return config(
                policy(20, 50),
                Set.of(
                        AgentCapability.CODE_READ,
                        AgentCapability.WORKSPACE_MUTATION
                ),
                toolSet("readFile", "finishTask"),
                "context-v1"
        );
    }

    private AgentExecutionConfig config(
            AgentRuntimePolicy policy,
            Set<AgentCapability> capabilities,
            ToolSetSnap toolSet,
            String contextPolicyVersion
    ) {
        return new AgentExecutionConfig(
                policy,
                capabilities,
                toolSet,
                contextPolicyVersion
        );
    }

    private AgentRuntimePolicy policy(int maxModelCalls, int maxToolCalls) {
        return new AgentRuntimePolicy(
                "test-model",
                maxModelCalls,
                maxToolCalls,
                2,
                3,
                4096,
                0.2
        );
    }

    private ToolSetSnap toolSet(String... names) {
        return new ToolSetSnap(1, List.of(names), TOOL_DIGEST);
    }
}
