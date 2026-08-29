package com.gitnova.service.agent.execution;

import java.time.Instant;
import java.util.Objects;

/** Durable projection of one user goal inside a Session. */
public record AgentTask(
        String taskId,
        String sessionId,
        String creationIdempotencyKey,
        long createdByActorId,
        Status status,
        String requestJson,
        String requestDigest,
        String currentRunId,
        long lastRunNumber,
        String terminalReason,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt
) {
    public enum Status {
        ACTIVE,
        WAITING_USER,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public AgentTask {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(creationIdempotencyKey, "creationIdempotencyKey");
        requireNonBlank(requestJson, "requestJson");
        requireDigest(requestDigest, "requestDigest");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (createdByActorId <= 0 || lastRunNumber < 0 || version < 0) {
            throw new IllegalArgumentException("Task counters and actor identity are invalid");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (status.terminal()) {
            requireNonBlank(terminalReason, "terminalReason");
            Objects.requireNonNull(terminalAt, "terminalAt must not be null for terminal Task");
            if (currentRunId != null) {
                throw new IllegalArgumentException("terminal Task must not retain currentRunId");
            }
        } else if (terminalReason != null || terminalAt != null) {
            throw new IllegalArgumentException("non-terminal Task must not contain terminal metadata");
        }
        if (status == Status.WAITING_USER && currentRunId != null) {
            throw new IllegalArgumentException("WAITING_USER Task must not retain currentRunId");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireDigest(String value, String field) {
        requireNonBlank(value, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lower-case SHA-256 digest");
        }
    }
}
