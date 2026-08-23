package com.gitnova.service.agent.workspace;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered, non-transactional batch submitted against one expected Workspace generation. */
public record WorkspaceMutationCommand(
        long expectedGeneration,
        List<PatchOperation> operations
) {
    public WorkspaceMutationCommand {
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("expectedGeneration must not be negative");
        }
        Objects.requireNonNull(operations, "operations must not be null");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }

        List<PatchOperation> copied = List.copyOf(operations);
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < copied.size(); index++) {
            PatchOperation operation = Objects.requireNonNull(
                    copied.get(index),
                    "operation must not be null"
            );
            if (operation.index() != index) {
                throw new IllegalArgumentException(
                        "operation indices must be contiguous and match list order"
                );
            }
            if (!paths.add(operation.filePath())) {
                throw new IllegalArgumentException(
                        "operations must not target the same file more than once"
                );
            }
        }
        operations = copied;
    }
}
