package com.gitnova.service.agent.journal;

import java.util.Locale;
import java.util.Objects;

public record RunJournalScope(
        String sessionId,
        String taskId,
        String runId,
        String workerId,
        long fencingToken,
        String executionConfigDigest
) {
    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    public RunJournalScope{
        requireNonBlank(sessionId,"sessionId");
        requireNonBlank(taskId,"taskId");
        requireNonBlank(runId,"runId");
        requireNonBlank(workerId,"workerId");
        if(executionConfigDigest==null||executionConfigDigest.isBlank()) throw new IllegalArgumentException("executionConfigDigest must not be null");
        if(fencingToken<=0) throw new IllegalArgumentException("fencingToken must be positive");
        requireSha256Digest(executionConfigDigest,"executionConfigDigest");
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
