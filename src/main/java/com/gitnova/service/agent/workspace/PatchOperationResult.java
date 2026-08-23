package com.gitnova.service.agent.workspace;

import java.util.Locale;
import java.util.Objects;

/**
 * Authoritative result for one requested file operation.
 *
 * <p>SHA-256 digests make later filesystem/DB reconciliation possible without storing file
 * contents in the result itself.</p>
 */
public record PatchOperationResult(
        int index,
        PatchOperationType type,
        String filePath,
        PatchOperationStatus status,
        String beforeSha256,
        String afterSha256,
        String errorCode,
        String message
) {
    public PatchOperationResult {
        if (index < 0) {
            throw new IllegalArgumentException("operation result index must not be negative");
        }
        Objects.requireNonNull(type, "operation result type must not be null");
        Objects.requireNonNull(filePath, "operation result filePath must not be null");
        if (filePath.isBlank()) {
            throw new IllegalArgumentException("operation result filePath must not be blank");
        }
        Objects.requireNonNull(status, "operation result status must not be null");
        beforeSha256 = normalizeDigest(beforeSha256, "beforeSha256");
        afterSha256 = normalizeDigest(afterSha256, "afterSha256");

        if (status == PatchOperationStatus.APPLIED) {
            if (errorCode != null || message != null) {
                throw new IllegalArgumentException(
                        "APPLIED operation result must not contain error information"
                );
            }
            requireAppliedDigests(type, beforeSha256, afterSha256);
        } else {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Non-applied operation result must contain errorCode"
                );
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Non-applied operation result must contain message"
                );
            }
            if (afterSha256 != null) {
                throw new IllegalArgumentException(
                        "Non-applied operation result must not contain afterSha256"
                );
            }
        }
    }

    public static PatchOperationResult applied(
            PatchOperation operation,
            String beforeSha256,
            String afterSha256
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        return new PatchOperationResult(
                operation.index(),
                operation.type(),
                operation.filePath(),
                PatchOperationStatus.APPLIED,
                beforeSha256,
                afterSha256,
                null,
                null
        );
    }

    public static PatchOperationResult failed(
            PatchOperation operation,
            String beforeSha256,
            String errorCode,
            String message
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        return new PatchOperationResult(
                operation.index(),
                operation.type(),
                operation.filePath(),
                PatchOperationStatus.FAILED,
                beforeSha256,
                null,
                errorCode,
                message
        );
    }

    public static PatchOperationResult notAttempted(PatchOperation operation) {
        return notAttempted(
                operation,
                "STOPPED_AFTER_PREVIOUS_FAILURE",
                "Operation was not attempted after an earlier operation failed"
        );
    }

    public static PatchOperationResult notAttempted(
            PatchOperation operation,
            String errorCode,
            String message
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        return new PatchOperationResult(
                operation.index(),
                operation.type(),
                operation.filePath(),
                PatchOperationStatus.NOT_ATTEMPTED,
                null,
                null,
                errorCode,
                message
        );
    }

    public boolean applied() {
        return status == PatchOperationStatus.APPLIED;
    }

    private static void requireAppliedDigests(
            PatchOperationType type,
            String beforeSha256,
            String afterSha256
    ) {
        switch (type) {
            case CREATE -> {
                if (beforeSha256 != null || afterSha256 == null) {
                    throw new IllegalArgumentException(
                            "Applied CREATE requires only afterSha256"
                    );
                }
            }
            case UPDATE -> {
                if (beforeSha256 == null || afterSha256 == null) {
                    throw new IllegalArgumentException(
                            "Applied UPDATE requires beforeSha256 and afterSha256"
                    );
                }
                if (beforeSha256.equals(afterSha256)) {
                    throw new IllegalArgumentException(
                            "Applied UPDATE must change the file digest"
                    );
                }
            }
            case DELETE -> {
                if (beforeSha256 == null || afterSha256 != null) {
                    throw new IllegalArgumentException(
                            "Applied DELETE requires only beforeSha256"
                    );
                }
            }
        }
    }

    private static String normalizeDigest(String digest, String fieldName) {
        if (digest == null) {
            return null;
        }
        String normalized = digest.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    fieldName + " must be a 64-character SHA-256 digest"
            );
        }
        return normalized;
    }
}
