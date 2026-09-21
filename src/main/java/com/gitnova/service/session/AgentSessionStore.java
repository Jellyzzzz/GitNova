package com.gitnova.service.session;

import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface AgentSessionStore {
    /**
     * Atomically creates:
     *
     * <ul>
     *   <li>Session projection in PROVISIONING</li>
     *   <li>1:1 Workspace projection in PROVISIONING</li>
     *   <li>SESSION_CREATED Step</li>
     * </ul>
     *
     * <p>A duplicate creationIdempotencyKey with identical semantics returns the
     * already-created Session. A conflicting request must fail.</p>
     */
    CreateResult createProvisioning(CreateSessionCommand command);

    /**
     * Atomically changes Workspace to READY and Session to ACTIVE and appends
     * WORKSPACE_MATERIALIZED.
     */
    AgentSession activate(WorkspaceActivation activation);

    /**
     * Records an unrecoverable provisioning failure and appends the
     * corresponding failure Step in the same transaction.
     */
    AgentSession failProvisioning(ProvisioningFailure failure);

    /** Commits generation/fingerprint and its Session Step together, before publishing an observation. */
    void recordWorkspaceState(WorkspaceStateChange change);

    record WorkspaceStateChange(
            String sessionId, String workspaceId, long epoch,
            long generationBefore, String fingerprintBefore,
            long generationAfter, String fingerprintAfter,
            WorkspaceExecutionPermit executionPermit
    ) {
        public WorkspaceStateChange {
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(workspaceId, "workspaceId");
            if (epoch < 0 || generationBefore < 0 || generationAfter < generationBefore) {
                throw new IllegalArgumentException("Workspace coordinates must not regress");
            }
            if ((fingerprintBefore != null && !fingerprintBefore.matches("[0-9a-f]{64}"))
                    || (fingerprintAfter != null && !fingerprintAfter.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Workspace fingerprint must be SHA-256 or unknown");
            }
            if (generationAfter == generationBefore && fingerprintBefore != null
                    && !Objects.equals(fingerprintBefore, fingerprintAfter)) {
                throw new IllegalArgumentException("A changed known tree must advance generation");
            }
            if (executionPermit != null && !workspaceId.equals(executionPermit.workspaceId().toString())) {
                throw new IllegalArgumentException("Execution permit belongs to another Workspace");
            }
        }
    }

    Optional<AgentSession> findById(String sessionId);

    Optional<AgentSession> findByCreationIdempotencyKey(String creationIdempotencyKey);

    final class CreationConflictException extends IllegalStateException {
        public CreationConflictException(String message) {
            super(message);
        }
    }

    record CreateResult(
            AgentSession session,
            boolean created
    ) {
        public CreateResult {
            Objects.requireNonNull(session, "session must not be null");
        }

        public static CreateResult created(AgentSession session) {
            return new CreateResult(session, true);
        }

        public static CreateResult alreadyExisting(AgentSession session) {
            return new CreateResult(session, false);
        }
    }
    record WorkspaceActivation(
            String eventId,
            String sessionId,
            String providerType,
            String providerRef,
            String manifestDigest,
            String fingerprint
    ) {
        public WorkspaceActivation {
            requireNonBlank(eventId, "eventId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(providerType, "providerType");
            requireNonBlank(providerRef, "providerRef");
            // The first Session milestone persists the current tree fingerprint.
            // A content-addressed Snapshot manifest is introduced by the Snapshot stage.
            if (manifestDigest != null && manifestDigest.isBlank()) {
                throw new IllegalArgumentException("manifestDigest must not be blank when present");
            }
            requireNonBlank(fingerprint, "fingerprint");
        }
    }

    record ProvisioningFailure(
            String eventId,
            String sessionId,
            String reasonCode,
            String safeMessage,
            boolean retryable
    ) {
        public ProvisioningFailure {
            requireNonBlank(eventId, "eventId");
            requireNonBlank(sessionId, "sessionId");
            requireNonBlank(reasonCode, "reasonCode");
            requireNonBlank(safeMessage, "safeMessage");
        }
    }

    /** Returns the creator's most recent Sessions in this repository, or an empty list. */
    List<AgentSession> selectByRepositoryAndCreator(Long repoId, Long actorId, int limit);

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

}
