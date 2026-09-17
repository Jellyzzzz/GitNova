package com.gitnova.service.agent.journal;

import java.util.Objects;
import com.gitnova.service.agent.context.ContextUsage;

/**
 * Durable intent for one external model invocation.
 *
 * <p>This event must be committed before ModelGateway.complete(...) is invoked.
 * Once committed, the model call is considered externally attempted even if the
 * Worker/JVM crashes before a corresponding MODEL_RESPONSE can be persisted.</p>
 */
public record ModelCallStartedPayload(
        String modelCallId,
        String requestDigest,
        long contextThroughRunStepSequence,
        long workspaceGeneration,
        Long contextThroughSessionSequence,
        ContextUsage.Measurement contextInput
) {
    public ModelCallStartedPayload(String modelCallId, String requestDigest,
                                   long contextThroughRunStepSequence, long workspaceGeneration) {
        this(modelCallId, requestDigest, contextThroughRunStepSequence, workspaceGeneration, null, null);
    }

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";

    public ModelCallStartedPayload {
        if ((contextInput == null) != (contextThroughSessionSequence == null)
                || (contextThroughSessionSequence != null && contextThroughSessionSequence < 0)) {
            throw new IllegalArgumentException("Context input requires a valid Session sequence");
        }
        requireNonBlank(
                modelCallId,
                "modelCallId"
        );

        requireSha256Digest(
                requestDigest,
                "requestDigest"
        );

        if (contextThroughRunStepSequence < 0) {
            throw new IllegalArgumentException(
                    "contextThroughRunStepSequence must not be negative"
            );
        }

        if (workspaceGeneration < 0) {
            throw new IllegalArgumentException(
                    "workspaceGeneration must not be negative"
            );
        }
    }

    private static void requireNonBlank(
            String value,
            String field
    ) {
        Objects.requireNonNull(
                value,
                field + " must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
    }

    private static void requireSha256Digest(
            String value,
            String field
    ) {
        requireNonBlank(value, field);

        if (!value.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(
                    field
                            + " must be a lower-case SHA-256 digest"
            );
        }
    }
}
