package com.gitnova.service.agent.workspace;

import java.util.Objects;

/**
 * One validated, single-file operation in an ordered Workspace mutation batch.
 *
 * <p>Size limits and repository-path safety are enforced at the Tool/Gateway boundaries.
 * This type only protects the mutually exclusive fields for each operation type.</p>
 */
public record PatchOperation(
        int index,
        PatchOperationType type,
        String filePath,
        String patch,
        String content
) {
    public PatchOperation {
        if (index < 0) {
            throw new IllegalArgumentException("operation index must not be negative");
        }
        Objects.requireNonNull(type, "operation type must not be null");
        Objects.requireNonNull(filePath, "operation filePath must not be null");
        if (filePath.isBlank()) {
            throw new IllegalArgumentException("operation filePath must not be blank");
        }

        switch (type) {
            case CREATE -> {
                Objects.requireNonNull(content, "CREATE content must not be null");
                if (patch != null) {
                    throw new IllegalArgumentException("CREATE must not contain patch");
                }
            }
            case UPDATE -> {
                Objects.requireNonNull(patch, "UPDATE patch must not be null");
                if (patch.isBlank()) {
                    throw new IllegalArgumentException("UPDATE patch must not be blank");
                }
                if (content != null) {
                    throw new IllegalArgumentException("UPDATE must not contain content");
                }
            }
            case DELETE -> {
                if (patch != null || content != null) {
                    throw new IllegalArgumentException(
                            "DELETE must not contain patch or content"
                    );
                }
            }
        }
    }

    public static PatchOperation create(int index, String filePath, String content) {
        return new PatchOperation(
                index,
                PatchOperationType.CREATE,
                filePath,
                null,
                content
        );
    }

    public static PatchOperation update(int index, String filePath, String patch) {
        return new PatchOperation(
                index,
                PatchOperationType.UPDATE,
                filePath,
                patch,
                null
        );
    }

    public static PatchOperation delete(int index, String filePath) {
        return new PatchOperation(
                index,
                PatchOperationType.DELETE,
                filePath,
                null,
                null
        );
    }
}
