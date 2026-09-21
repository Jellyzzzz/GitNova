package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.model.ModelThinking;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        root.put("schemaVersion", 99);
        String unsupported = objectMapper.writeValueAsString(root);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                codec.decode(unsupported);
            }
        });
    }

    @Test
    void fourContextPoliciesRoundTripWithoutChangingLegacyEnabledContract() throws Exception {
        var old = standardConfig();
        var digests = new java.util.HashSet<String>();
        for (boolean externalization : List.of(false, true)) {
            for (boolean summary : List.of(false, true)) {
                var config = new AgentExecutionConfig(old.policy(), old.capabilities(), old.toolSet(),
                        old.contextPolicyVersion(), new ObservationPolicy(4096, 1024, externalization),
                        new com.gitnova.service.agent.context.ContextBudget(18000, 1024, .6, .9, 2, summary));
                var encoded = codec.encode(config);
                digests.add(encoded.digest());
                assertEquals(config, codec.decode(encoded.json()));
                assertEquals(encoded, codec.encode(codec.decode(encoded.json())));
                var json = (ObjectNode) objectMapper.readTree(encoded.json());
                assertEquals(externalization && summary ? 3 : 4, json.path("schemaVersion").asInt());
                if (externalization && summary) {
                    assertFalse(json.path("contextBudget").has("summaryEnabled"));
                    assertFalse(json.path("observationPolicy").has("externalizationEnabled"));
                } else {
                    json.withObject("/contextBudget").remove("summaryEnabled");
                    assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
                }
            }
        }
        assertEquals(4, digests.size());
    }

    @Test
    void observationBudgetIsFrozenAndChangesTheDigest() throws Exception {
        AgentExecutionConfig old = standardConfig();
        AgentExecutionConfig configured = new AgentExecutionConfig(old.policy(), old.capabilities(),
                old.toolSet(), old.contextPolicyVersion(), new ObservationPolicy(2048, 512));
        var encoded = codec.encode(configured);
        assertEquals(2, objectMapper.readTree(encoded.json()).path("schemaVersion").asInt());
        assertEquals(configured, codec.decode(encoded.json()));
        assertEquals(encoded, codec.encode(codec.decode(encoded.json())));

        AgentExecutionConfig changed = new AgentExecutionConfig(old.policy(), old.capabilities(),
                old.toolSet(), old.contextPolicyVersion(), new ObservationPolicy(4096, 512));
        assertNotEquals(encoded.digest(), codec.encode(changed).digest());
    }

    @Test
    void shouldFreezeContextBudgetAndPreserveOlderWireContracts() throws Exception {
        var old = standardConfig();
        var budget = new com.gitnova.service.agent.context.ContextBudget(32000, 2000, 0.8, 0.9, 4);
        var configured = new AgentExecutionConfig(old.policy(), old.capabilities(), old.toolSet(), old.contextPolicyVersion(),
                new ObservationPolicy(2048, 512), budget);
        var encoded = codec.encode(configured);
        assertEquals(3, objectMapper.readTree(encoded.json()).path("schemaVersion").asInt());
        assertEquals(configured, codec.decode(encoded.json()));
        assertEquals(encoded, codec.encode(codec.decode(encoded.json())));
        var changed = new AgentExecutionConfig(old.policy(), old.capabilities(), old.toolSet(), old.contextPolicyVersion(),
                new ObservationPolicy(2048, 512), new com.gitnova.service.agent.context.ContextBudget(64000, 2000, 0.8, 0.9, 4));
        assertNotEquals(encoded.digest(), codec.encode(changed).digest());
        assertNull(codec.decode(codec.encode(old).json()).contextBudget());
        ObjectNode root = (ObjectNode) objectMapper.readTree(encoded.json());
        root.withObject("/contextBudget").putNull("summaryTriggerRatio");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
        root.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
    }

    @Test
    void legacyContractKeepsItsJsonDigestAndDoesNotAcquireNewBudgets() throws Exception {
        var encoded = codec.encode(standardConfig());
        var json = objectMapper.readTree(encoded.json());
        assertEquals(1, json.path("schemaVersion").asInt());
        assertFalse(json.has("observationPolicy"));
        assertNull(codec.decode(encoded.json()).observationPolicy());
        assertEquals(encoded, codec.encode(codec.decode(encoded.json())));
    }

    @Test
    void newContractRequiresValidExplicitBudgetsAndLegacyCannotSmuggleThemIn() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.readTree(codec.encode(standardConfig()).json());
        root.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
        root.putNull("observationPolicy");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
        root.putObject("observationPolicy").put("maxInlineTokens", 100).put("maxPreviewTokens", 100);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
        root.withObject("/observationPolicy").put("maxPreviewTokens", 50);
        assertEquals(new ObservationPolicy(100, 50), codec.decode(root.toString()).observationPolicy());
        root.put("schemaVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(root.toString()));
    }

    @Test
    void thinkingControlsAreCanonicalFrozenAndRoundTripWithOrWithoutContextBudgets() throws Exception {
        var old = standardConfig();
        for (var thinking : List.of(ModelThinking.disabled(), new ModelThinking("enabled", "ultra"))) {
            var policy = new AgentRuntimePolicy("test-model", 20, 50, 2, 3, 8192, 0.2, thinking);
            for (boolean budgets : List.of(false, true)) {
                var config = new AgentExecutionConfig(policy, old.capabilities(), old.toolSet(), "context-v1",
                        budgets ? new ObservationPolicy(4096, 1024, false) : null,
                        budgets ? new com.gitnova.service.agent.context.ContextBudget(128000, 2000, .8, .9, 4, true) : null);
                var encoded = codec.encode(config);
                var json = objectMapper.readTree(encoded.json());
                assertEquals(5, json.path("schemaVersion").asInt());
                assertEquals(thinking.mode(), json.path("policy").path("thinking").path("mode").asText());
                assertEquals(config, codec.decode(encoded.json()));
                assertEquals(encoded, codec.encode(codec.decode(encoded.json())));
                assertNotEquals(codec.encode(old).digest(), encoded.digest());
            }
        }
        var encodedOld = codec.encode(old);
        assertNull(codec.decode(encodedOld.json()).policy().thinking());
        assertEquals(encodedOld, codec.encode(codec.decode(encodedOld.json())));
    }

    @Test
    void freezesSeparateMainAndSummaryThinkingAndPreservesOlderDigests() throws Exception {
        var old = standardConfig();
        var policy = new AgentRuntimePolicy("test-model", 40, 80, 2, 3, 32768, 0.0,
                new ModelThinking("enabled", "max"), new ModelThinking("enabled", "xhigh"));
        var config = new AgentExecutionConfig(policy, old.capabilities(), old.toolSet(), "context-v1");
        var encoded = codec.encode(config);
        var json = (ObjectNode) objectMapper.readTree(encoded.json());
        assertEquals(6, json.path("schemaVersion").asInt());
        assertEquals("high", json.at("/policy/summaryThinking/effort").asText());
        assertEquals(config, codec.decode(encoded.json()));
        assertEquals(encoded, codec.encode(codec.decode(encoded.json())));
        json.put("schemaVersion", 5);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
        json.withObject("/policy").remove("summaryThinking");
        assertNull(codec.decode(json.toString()).policy().summaryThinking());
        var legacy = codec.encode(codec.decode(json.toString()));
        assertEquals(5, objectMapper.readTree(legacy.json()).path("schemaVersion").asInt());
        assertEquals(legacy, codec.encode(codec.decode(legacy.json())));
        json.put("schemaVersion", 6);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
    }

    @Test
    void frozenThinkingCannotBeMissingOrHiddenInAnOlderSchema() throws Exception {
        var json = (ObjectNode) objectMapper.readTree(codec.encode(standardConfig()).json());
        json.put("schemaVersion", 5);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
        var thinking = json.withObject("/policy").putObject("thinking").put("mode", "enabled").put("effort", "high");
        assertEquals(new ModelThinking("enabled", "high"), codec.decode(json.toString()).policy().thinking());
        thinking.put("effort", "medium"); // Accepted in configuration, not in already-frozen canonical JSON.
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
        thinking.put("effort", "high");
        json.put("schemaVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.toString()));
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
