package com.gitnova.service.agent.execution;

import com.gitnova.service.agent.runtime.AgentExecutionConfig;

import java.time.Instant;
import java.util.Objects;

/** Durable logical execution attempt; one Run may survive Worker/JVM takeover. */
public record AgentRun(
        String runId,
        String sessionId,
        String taskId,
        long runNumber,
        String predecessorRunId,
        Status status,
        long lastRunStepSequence,
        String leaseOwner,
        Instant leaseUntil,
        Long currentFencingToken,
        AgentExecutionConfig executionConfig,
        String executionConfigDigest,
        String terminationReason,
        long version,
        Instant createdAt,
        Instant claimedAt,
        Instant lastHeartbeatAt,
        Instant finishedAt,
        Instant updatedAt
) {
    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
        }
    }

    public AgentRun {
        requireNonBlank(runId, "runId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(taskId, "taskId");
        Objects.requireNonNull(executionConfig, "executionConfig must not be null");
        requireDigest(executionConfigDigest, "executionConfigDigest");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (runNumber <= 0 || lastRunStepSequence < 0 || version < 0) {
            throw new IllegalArgumentException("Run counters must be valid");
        }
        if (currentFencingToken != null && currentFencingToken <= 0) {
            throw new IllegalArgumentException("currentFencingToken must be positive when present");
        }
        if (status == Status.QUEUED
                && (leaseOwner != null || leaseUntil != null || currentFencingToken != null)) {
            throw new IllegalArgumentException("QUEUED Run must not have execution ownership");
        }
        if (status == Status.RUNNING
                && (leaseOwner == null || leaseUntil == null || currentFencingToken == null)) {
            throw new IllegalArgumentException("RUNNING Run must have lease and fencing ownership");
        }
        if (status.terminal()) {
            requireNonBlank(terminationReason, "terminationReason");
            Objects.requireNonNull(finishedAt, "finishedAt must not be null for terminal Run");
            if (leaseOwner != null || leaseUntil != null) {
                throw new IllegalArgumentException("terminal Run must not retain a lease");
            }
        } else if (terminationReason != null || finishedAt != null) {
            throw new IllegalArgumentException("non-terminal Run must not contain terminal metadata");
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
