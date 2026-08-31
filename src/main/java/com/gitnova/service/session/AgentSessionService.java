package com.gitnova.service.session;

import com.gitnova.service.agent.workspace.WorkspaceHandle;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;
import com.gitnova.service.agent.workspace.WorkspaceProvider;
import com.gitnova.service.agent.workspace.WorkspaceProvisionException;
import com.gitnova.service.agent.workspace.LocalWorkspaceRegistry;
import com.gitnova.service.agent.workspace.WorkspaceSpec;
import com.gitnova.service.agent.workspace.WorkspaceTreeFingerprint;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Application service coordinating the durable Session creation saga.
 *
 * <p>MySQL transactions deliberately do not span filesystem materialization:
 * first persist PROVISIONING, then publish the Workspace, then atomically commit
 * ACTIVE/READY plus WORKSPACE_MATERIALIZED.</p>
 */
@Service
public class AgentSessionService {

    private final AgentSessionStore sessionStore;
    private final WorkspaceProvider workspaceProvider;
    private final LocalWorkspaceRegistry workspaceRegistry;

    public AgentSessionService(
            AgentSessionStore sessionStore,
            WorkspaceProvider workspaceProvider,
            LocalWorkspaceRegistry workspaceRegistry
    ) {
        this.sessionStore = Objects.requireNonNull(
                sessionStore,
                "sessionStore must not be null"
        );
        this.workspaceProvider = Objects.requireNonNull(
                workspaceProvider,
                "workspaceProvider must not be null"
        );
        this.workspaceRegistry = Objects.requireNonNull(
                workspaceRegistry,
                "workspaceRegistry must not be null"
        );
    }

    /** Creates or idempotently resumes creation of one persistent Session. */
    public AgentSession create(CreateSessionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AgentSession session = sessionStore.createProvisioning(command).session();
        if (session.status() == AgentSession.Status.ACTIVE) {
            return session;
        }
        if (session.status() != AgentSession.Status.PROVISIONING) {
            throw new IllegalStateException(
                    "Session creation cannot continue from status " + session.status()
            );
        }

        WorkspaceSpec trustedSpec = new WorkspaceSpec(
                session.workspaceId(),
                session.repoKey(),
                session.source()
        );
        try {
            WorkspaceHandle handle = workspaceProvider.provision(trustedSpec);
            String fingerprint = WorkspaceTreeFingerprint.capture(handle.root());
            AgentSession active = sessionStore.activate(new AgentSessionStore.WorkspaceActivation(
                    workspaceMaterializedEventId(session),
                    session.sessionId(),
                    workspaceProvider.providerType(),
                    workspaceProvider.providerReference(handle),
                    null,
                    fingerprint
            ));
            workspaceRegistry.register(handle);
            return active;
        } catch (WorkspaceProvisionException exception) {
            recordProvisioningFailure(session, exception.reason().name(), retryable(exception));
            throw exception;
        } catch (WorkspaceOperationException exception) {
            recordProvisioningFailure(session, exception.reason().name(), false);
            throw exception;
        }
    }

    public AgentSession require(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionStore.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Session: " + sessionId));
    }

    private void recordProvisioningFailure(
            AgentSession session,
            String reasonCode,
            boolean retryable
    ) {
        sessionStore.failProvisioning(new AgentSessionStore.ProvisioningFailure(
                workspaceProvisioningFailedEventId(session, reasonCode),
                session.sessionId(),
                reasonCode,
                "Workspace provisioning failed: " + reasonCode,
                retryable
        ));
    }

    private static String workspaceMaterializedEventId(AgentSession session) {
        return "workspace:materialized:" + session.workspaceId();
    }

    private static String workspaceProvisioningFailedEventId(
            AgentSession session,
            String reasonCode
    ) {
        return "workspace:provisioning-failed:"
                + session.workspaceId()
                + ":"
                + reasonCode;
    }

    private static boolean retryable(WorkspaceProvisionException exception) {
        return switch (exception.reason()) {
            case STORAGE_UNAVAILABLE, FILESYSTEM_FAILURE -> true;
            case SNAPSHOT_NOT_FOUND,
                 SNAPSHOT_CORRUPT,
                 INVALID_REPOSITORY_PATH,
                 PATH_COLLISION,
                 WORKSPACE_CONFLICT,
                 ATOMIC_PUBLISH_UNAVAILABLE -> false;
        };
    }
}
