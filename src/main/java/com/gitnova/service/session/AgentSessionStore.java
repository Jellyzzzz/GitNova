package com.gitnova.service.session;

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

    Optional<AgentSession> findById(String sessionId);

    Optional<AgentSession> findByCreationIdempotencyKey(String creationIdempotencyKey);


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

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

}
