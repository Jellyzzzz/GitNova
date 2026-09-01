package com.gitnova.service.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.runtime.ToolSetSnap;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Creates the stable Tool contract frozen into an Agent Run. */
@Component
public final class ToolSetSnapFactory {
    private static final int SCHEMA_VERSION = 1;

    private final CanonicalJsonCodec canonicalJson;

    public ToolSetSnapFactory(CanonicalJsonCodec canonicalJson) {
        this.canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson must not be null"
        );
    }

    public ToolSetSnap create(List<ToolDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("definitions must not be empty");
        }

        ArrayList<ToolDefinition> ordered = new ArrayList<>(definitions.size());
        for (ToolDefinition definition : definitions) {
            ordered.add(Objects.requireNonNull(
                    definition,
                    "definitions must not contain null"
            ));
        }
        ordered.sort(new Comparator<>() {
            @Override
            public int compare(ToolDefinition left, ToolDefinition right) {
                return left.name().compareTo(right.name());
            }
        });

        ArrayList<String> names = new ArrayList<>(ordered.size());
        ArrayNode normalizedDefinitions = canonicalJson
                .objectNode()
                .putArray("definitions");
        String previousName = null;

        for (ToolDefinition definition : ordered) {
            if (definition.name().equals(previousName)) {
                throw new IllegalArgumentException(
                        "Duplicate tool definition name: " + definition.name()
                );
            }
            previousName = definition.name();
            names.add(definition.name());

            ObjectNode normalized = normalizedDefinitions.addObject();
            normalized.put("name", definition.name());
            normalized.put("description", definition.description());
            normalized.set("inputSchema", definition.inputSchema());
        }

        String definitionDigest = canonicalJson
                .encode(normalizedDefinitions)
                .digest();
        return new ToolSetSnap(
                SCHEMA_VERSION,
                names,
                definitionDigest
        );
    }
}
