package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRuntimePolicy;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stable wire-format codec for the complete Frozen Execution Contract. */
@Component
public final class AgentExecutionConfigCodec {
    private static final int SCHEMA_VERSION = 1;

    private final CanonicalJsonCodec canonicalJson;
    private final ObjectMapper objectMapper;

    public AgentExecutionConfigCodec(
            CanonicalJsonCodec canonicalJson,
            ObjectMapper objectMapper
    ) {
        this.canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    public CanonicalJsonCodec.EncodedJson encode(AgentExecutionConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        ObjectNode root = canonicalJson.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        encodePolicy(root.putObject("policy"), config.policy());

        ArrayNode capabilities = root.putArray("capabilities");
        for (AgentCapability capability : AgentCapability.values()) {
            if (config.capabilities().contains(capability)) {
                capabilities.add(capability.name());
            }
        }

        ToolSetSnap toolSet = config.toolSet();
        ObjectNode toolSetNode = root.putObject("toolSet");
        toolSetNode.put("schemaVersion", toolSet.schemaVersion());
        ArrayNode names = toolSetNode.putArray("enabledDefinitionNames");
        for (String name : toolSet.enabledDefinitionNames()) {
            names.add(name);
        }
        toolSetNode.put("definitionDigest", toolSet.definitionDigest());
        root.put("contextPolicyVersion", config.contextPolicyVersion());

        return canonicalJson.encode(root);
    }

    public AgentExecutionConfig decode(String json) {
        Objects.requireNonNull(json, "json must not be null");
        if (json.isBlank()) {
            throw invalid("execution config JSON must not be blank");
        }

        try {
            ObjectNode root = requireObject(objectMapper.readTree(json), "execution config");
            int schemaVersion = requireInt(root, "schemaVersion");
            if (schemaVersion != SCHEMA_VERSION) {
                throw invalid(
                        "unsupported execution config schemaVersion: " + schemaVersion
                );
            }

            AgentRuntimePolicy policy = decodePolicy(
                    requireObject(root.get("policy"), "policy")
            );
            Set<AgentCapability> capabilities = decodeCapabilities(
                    requireArray(root.get("capabilities"), "capabilities")
            );
            ToolSetSnap toolSet = decodeToolSet(
                    requireObject(root.get("toolSet"), "toolSet")
            );
            String contextPolicyVersion = requireText(
                    root,
                    "contextPolicyVersion"
            );
            return new AgentExecutionConfig(
                    policy,
                    capabilities,
                    toolSet,
                    contextPolicyVersion
            );
        } catch (JsonProcessingException exception) {
            throw invalid("execution config JSON is invalid", exception);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("Invalid execution config:")) {
                throw exception;
            }
            throw invalid("execution config values are invalid", exception);
        }
    }

    private void encodePolicy(ObjectNode node, AgentRuntimePolicy policy) {
        node.put("model", policy.model());
        node.put("maxModelCalls", policy.maxModelCalls());
        node.put("maxToolCalls", policy.maxToolCalls());
        node.put("maxProtocolCorrections", policy.maxProtocolCorrections());
        node.put("maxFinalDraftCorrections", policy.maxFinalDraftCorrections());
        if (policy.maxOutputTokens() == null) {
            node.putNull("maxOutputTokens");
        } else {
            node.put("maxOutputTokens", policy.maxOutputTokens());
        }
        if (policy.temperature() == null) {
            node.putNull("temperature");
        } else {
            node.put("temperature", policy.temperature());
        }
    }

    private AgentRuntimePolicy decodePolicy(ObjectNode node) {
        return new AgentRuntimePolicy(
                requireText(node, "model"),
                requireInt(node, "maxModelCalls"),
                requireInt(node, "maxToolCalls"),
                requireInt(node, "maxProtocolCorrections"),
                requireInt(node, "maxFinalDraftCorrections"),
                requireNullableInt(node, "maxOutputTokens"),
                requireNullableDouble(node, "temperature")
        );
    }

    private Set<AgentCapability> decodeCapabilities(ArrayNode node) {
        EnumSet<AgentCapability> capabilities = EnumSet.noneOf(AgentCapability.class);
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw invalid("capabilities must contain non-blank names");
            }
            AgentCapability capability;
            try {
                capability = AgentCapability.valueOf(value.textValue());
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        "unknown Agent capability: " + value.textValue(),
                        exception
                );
            }
            if (!capabilities.add(capability)) {
                throw invalid("duplicate Agent capability: " + capability.name());
            }
        }
        return Set.copyOf(capabilities);
    }

    private ToolSetSnap decodeToolSet(ObjectNode node) {
        ArrayNode namesNode = requireArray(
                node.get("enabledDefinitionNames"),
                "toolSet.enabledDefinitionNames"
        );
        List<String> names = new ArrayList<>(namesNode.size());
        for (JsonNode value : namesNode) {
            if (!value.isTextual()) {
                throw invalid("toolSet.enabledDefinitionNames must contain names");
            }
            names.add(value.textValue());
        }
        return new ToolSetSnap(
                requireInt(node, "schemaVersion"),
                names,
                requireText(node, "definitionDigest")
        );
    }

    private static ObjectNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field + " must be an object");
        }
        return (ObjectNode) node;
    }

    private static ArrayNode requireArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw invalid(field + " must be an array");
        }
        return (ArrayNode) node;
    }

    private static String requireText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be text");
        }
        return value.textValue();
    }

    private static int requireInt(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static Integer requireNullableInt(ObjectNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be null or a 32-bit integer");
        }
        return value.intValue();
    }

    private static Double requireNullableDouble(ObjectNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid(field + " must be null or a number");
        }
        return value.doubleValue();
    }

    private static JsonNode requirePresent(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw invalid(field + " must be present");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid execution config: " + message);
    }

    private static IllegalArgumentException invalid(
            String message,
            Throwable cause
    ) {
        return new IllegalArgumentException(
                "Invalid execution config: " + message,
                cause
        );
    }
}
