package com.gitnova.service.agent.workspace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * One validated, single-file operation in an ordered Workspace mutation batch.
 *
 * <p>Size limits and repository-path safety are enforced at the Tool/Gateway boundaries.
 * This type protects mutually exclusive fields and bounds exact-edit inputs.</p>
 */
public record PatchOperation(
        int index,
        PatchOperationType type,
        String filePath,
        String patch,
        String content,
        List<TextEdit> edits
) {
    public static final int MAX_EDITS = 32;
    public static final int MAX_EDIT_TEXT_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_EDIT_BYTES = 4 * 1024 * 1024;

    public PatchOperation {
        if (index < 0) {
            throw new IllegalArgumentException("operation index must not be negative");
        }
        Objects.requireNonNull(type, "operation type must not be null");
        Objects.requireNonNull(filePath, "operation filePath must not be null");
        if (filePath.isBlank()) {
            throw new IllegalArgumentException("operation filePath must not be blank");
        }
        if (type != PatchOperationType.EDIT && edits != null) {
            throw new IllegalArgumentException(type + " must not contain edits");
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
            case EDIT -> {
                if (patch != null || content != null) {
                    throw new IllegalArgumentException("EDIT must not contain patch or content");
                }
                if (edits == null || edits.isEmpty() || edits.size() > MAX_EDITS) {
                    throw new IllegalArgumentException("edits must contain between 1 and " + MAX_EDITS + " replacements");
                }
                edits = List.copyOf(edits);
                long totalBytes = 0;
                for (TextEdit edit : edits) {
                    totalBytes += edit.oldText().getBytes(StandardCharsets.UTF_8).length;
                    totalBytes += edit.newText().getBytes(StandardCharsets.UTF_8).length;
                }
                if (totalBytes > MAX_TOTAL_EDIT_BYTES) {
                    throw new IllegalArgumentException("Combined edit text exceeds the byte limit");
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
                content,
                null
        );
    }

    public static PatchOperation update(int index, String filePath, String patch) {
        return new PatchOperation(
                index,
                PatchOperationType.UPDATE,
                filePath,
                patch,
                null,
                null
        );
    }

    public static PatchOperation delete(int index, String filePath) {
        return new PatchOperation(
                index,
                PatchOperationType.DELETE,
                filePath,
                null,
                null,
                null
        );
    }

    public static PatchOperation edit(int index, String filePath, List<TextEdit> edits) {
        return new PatchOperation(index, PatchOperationType.EDIT, filePath, null, null, edits);
    }

    public record TextEdit(String oldText, String newText) {
        public TextEdit {
            if (oldText == null || oldText.isEmpty() || newText == null) {
                throw new IllegalArgumentException("oldText must be non-empty and newText must be a string (empty is allowed)");
            }
            if (oldText.indexOf('\0') >= 0 || newText.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Edit text must not contain NUL characters");
            }
            if (oldText.length() > MAX_EDIT_TEXT_BYTES || newText.length() > MAX_EDIT_TEXT_BYTES
                    || oldText.getBytes(StandardCharsets.UTF_8).length > MAX_EDIT_TEXT_BYTES
                    || newText.getBytes(StandardCharsets.UTF_8).length > MAX_EDIT_TEXT_BYTES) {
                throw new IllegalArgumentException("Each edit text exceeds the byte limit");
            }
        }
    }
}
