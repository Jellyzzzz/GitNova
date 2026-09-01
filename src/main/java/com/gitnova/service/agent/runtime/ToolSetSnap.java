package com.gitnova.service.agent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ToolSetSnap(
        int schemaVersion,
        List<String> enabledDefinitionNames,
        String definitionDigest
) {
    public ToolSetSnap {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(
                enabledDefinitionNames,
                "enabledDefinitionNames must not be null"
        );
        Objects.requireNonNull(
                definitionDigest,
                "definitionDigest must not be null"
        );

        if (definitionDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "definitionDigest must not be blank"
            );
        }
        Set<String> uniqueNames = new TreeSet<>();
        for (String name : enabledDefinitionNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            uniqueNames.add(name);
        }
        if (uniqueNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "enabledToolNames must not be empty"
            );
        }
        enabledDefinitionNames = List.copyOf(uniqueNames);
    }
}
