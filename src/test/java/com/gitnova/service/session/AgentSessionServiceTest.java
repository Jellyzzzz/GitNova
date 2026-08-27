package com.gitnova.service.session;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceHandle;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceProvider;
import com.gitnova.service.agent.workspace.WorkspaceProvisionException;
import com.gitnova.service.agent.workspace.WorkspaceSpec;
import com.gitnova.service.agent.workspace.WorkspaceStatus;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

    private static final RepoKey REPO_KEY = RepoKey.of(7L, 42L);
    private static final SnapshotScope SOURCE = new SnapshotScope(
            GitObjectId.of("a".repeat(40))
    );

    @Mock
    AgentSessionStore sessionStore;

    @Mock
    WorkspaceProvider workspaceProvider;

    @TempDir
    Path tempDir;

    @Test
    void shouldProvisionThePersistedWorkspaceIdentityAndActivateTheSession() throws Exception {
        CreateSessionCommand command = CreateSessionCommand.prepare(
                "request-123",
                11L,
                REPO_KEY,
                SOURCE
        );
        AgentSession persisted = session(
                "persisted-session",
                WorkspaceId.generate(),
                AgentSession.Status.PROVISIONING,
                1L
        );
        AgentSession active = session(
                persisted.sessionId(),
                persisted.workspaceId(),
                AgentSession.Status.ACTIVE,
                2L
        );
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(root.resolve("App.java"), "class App {}\n");
        WorkspaceHandle handle = new WorkspaceHandle(
                persisted.workspaceId(),
                persisted.repoKey(),
                persisted.source(),
                root,
                WorkspaceStatus.READY,
                0L
        );

        when(sessionStore.createProvisioning(command)).thenReturn(
                AgentSessionStore.CreateResult.alreadyExisting(persisted)
        );
        when(workspaceProvider.provision(any())).thenReturn(handle);
        when(workspaceProvider.providerType()).thenReturn("local-filesystem");
        when(workspaceProvider.providerReference(handle)).thenReturn(root.toString());
        when(sessionStore.activate(any())).thenReturn(active);

        AgentSession result = service().create(command);

        assertSame(active, result);
        ArgumentCaptor<WorkspaceSpec> spec = ArgumentCaptor.forClass(WorkspaceSpec.class);
        verify(workspaceProvider).provision(spec.capture());
        assertEquals(persisted.workspaceId(), spec.getValue().workspaceId());
        assertEquals(persisted.repoKey(), spec.getValue().repoKey());
        assertEquals(persisted.source(), spec.getValue().snapshotScope());

        ArgumentCaptor<AgentSessionStore.WorkspaceActivation> activation =
                ArgumentCaptor.forClass(AgentSessionStore.WorkspaceActivation.class);
        verify(sessionStore).activate(activation.capture());
        assertEquals(
                "workspace:materialized:" + persisted.workspaceId(),
                activation.getValue().eventId()
        );
        assertEquals(64, activation.getValue().fingerprint().length());
    }

    @Test
    void shouldReturnAnAlreadyActiveSessionWithoutProvisioningAgain() {
        CreateSessionCommand command = CreateSessionCommand.prepare(
                "request-123",
                11L,
                REPO_KEY,
                SOURCE
        );
        AgentSession active = session(
                command.sessionId(),
                command.workspaceId(),
                AgentSession.Status.ACTIVE,
                2L
        );
        when(sessionStore.createProvisioning(command)).thenReturn(
                AgentSessionStore.CreateResult.alreadyExisting(active)
        );

        assertSame(active, service().create(command));

        verify(workspaceProvider, never()).provision(any());
        verify(sessionStore, never()).activate(any());
    }

    @Test
    void shouldPersistRetryableProvisioningFailureBeforePropagatingIt() {
        CreateSessionCommand command = CreateSessionCommand.prepare(
                "request-123",
                11L,
                REPO_KEY,
                SOURCE
        );
        AgentSession provisioning = session(
                command.sessionId(),
                command.workspaceId(),
                AgentSession.Status.PROVISIONING,
                1L
        );
        WorkspaceProvisionException failure = new WorkspaceProvisionException(
                "raw provider detail must not be persisted",
                WorkspaceProvisionException.Reason.STORAGE_UNAVAILABLE
        );
        when(sessionStore.createProvisioning(command)).thenReturn(
                AgentSessionStore.CreateResult.created(provisioning)
        );
        when(workspaceProvider.provision(any())).thenThrow(failure);
        when(sessionStore.failProvisioning(any())).thenReturn(provisioning);

        WorkspaceProvisionException thrown = assertThrows(
                WorkspaceProvisionException.class,
                () -> service().create(command)
        );

        assertSame(failure, thrown);
        ArgumentCaptor<AgentSessionStore.ProvisioningFailure> persistedFailure =
                ArgumentCaptor.forClass(AgentSessionStore.ProvisioningFailure.class);
        verify(sessionStore).failProvisioning(persistedFailure.capture());
        assertEquals("STORAGE_UNAVAILABLE", persistedFailure.getValue().reasonCode());
        assertTrue(persistedFailure.getValue().retryable());
        assertEquals(
                "Workspace provisioning failed: STORAGE_UNAVAILABLE",
                persistedFailure.getValue().safeMessage()
        );
    }

    private AgentSessionService service() {
        return new AgentSessionService(sessionStore, workspaceProvider);
    }

    private AgentSession session(
            String sessionId,
            WorkspaceId workspaceId,
            AgentSession.Status status,
            long sequence
    ) {
        Instant now = Instant.parse("2026-08-27T08:00:00Z");
        return new AgentSession(
                sessionId,
                "request-123",
                11L,
                REPO_KEY,
                workspaceId,
                SOURCE,
                status,
                sequence,
                sequence,
                now,
                now
        );
    }
}
