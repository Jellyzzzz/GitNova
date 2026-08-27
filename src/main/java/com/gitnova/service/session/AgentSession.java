package com.gitnova.service.session;

import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.storage.RepoKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain view of one persistent coding Session and its unique Logical Workspace.
 */
public record AgentSession(
        String sessionId,
        String creationIdempotencyKey,
        long createdByActorId,
        RepoKey repoKey,
        WorkspaceId workspaceId,
        SnapshotScope source,
        Status status,
        long lastSessionSequence,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        PROVISIONING,
        ACTIVE,
        CLOSING,
        CLOSED,
        FAILED
    }
    public AgentSession {
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(creationIdempotencyKey, "creationIdempotencyKey");
        Objects.requireNonNull(repoKey, "repoKey must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (createdByActorId <= 0) {
            throw new IllegalArgumentException("createdByActorId must be positive");
        }
        if (lastSessionSequence < 0) {
            throw new IllegalArgumentException(
                    "lastSessionSequence must not be negative"
            );
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt must not be before createdAt"
            );
        }
    }
    public boolean acceptsNewTasks() {
        return status == Status.ACTIVE;
    }

    public boolean terminal() {
        return status == Status.FAILED || status == Status.CLOSED;
    }

    public boolean canTransitionTo(Status target) {
        Objects.requireNonNull(target, "target must not be null");
        return switch (status) {
            case PROVISIONING -> target == Status.FAILED || target == Status.ACTIVE;
            case ACTIVE -> target == Status.CLOSING || target == Status.FAILED;
            case CLOSING -> target == Status.CLOSED || target == Status.FAILED;
            case CLOSED, FAILED -> false;
        };
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
