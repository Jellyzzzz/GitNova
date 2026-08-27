package com.gitnova.service.session;

import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.storage.RepoKey;

import java.util.Objects;
import java.util.UUID;
/**
 * Trusted command for creating one Session and its logical Workspace identity.
 *
 * <p>The creationIdempotencyKey is supplied by the application/API idempotency
 * boundary. Session and Workspace identities are allocated before the first
 * database attempt and remain stable inside this command.</p>
 */
public record CreateSessionCommand(
        String creationIdempotencyKey,
        String sessionId,
        WorkspaceId workspaceId,
        long createdByActorId,
        RepoKey repoKey,
        SnapshotScope source
) {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    public CreateSessionCommand {
        requireNonBlank(creationIdempotencyKey, "creationIdempotencyKey");
        requireNonBlank(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(repoKey, "repoKey must not be null");
        Objects.requireNonNull(source, "source must not be null");

        if (creationIdempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "creationIdempotencyKey exceeds the length limit"
            );
        }
        if (createdByActorId <= 0) {
            throw new IllegalArgumentException("createdByActorId must be positive");
        }
    }
    public static CreateSessionCommand prepare(
            String creationIdempotencyKey,
            long createdByActorId,
            RepoKey repoKey,
            SnapshotScope source
    ) {
        return new CreateSessionCommand(
                creationIdempotencyKey,
                UUID.randomUUID().toString(),
                WorkspaceId.generate(),
                createdByActorId,
                repoKey,
                source
        );
    }
    /**
     * Globally names the logical SESSION_CREATED event.
     *
     * <p>A retried create request uses the same event identity even if the
     * application accidentally prepares another candidate sessionId. The Store
     * must resolve the existing creationIdempotencyKey before attempting inserts.</p>
     */
    public String sessionCreatedEventId() {
        return "session:create:" + creationIdempotencyKey;
    }

    public String workspaceMaterializedEventId() {
        return "workspace:materialized:" + workspaceId;
    }
    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
