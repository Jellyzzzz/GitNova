package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisAgentSessionStoreTest {

    private static final RepoKey REPO_KEY = RepoKey.of(7L, 42L);
    private static final SnapshotScope SOURCE = new SnapshotScope(
            GitObjectId.of("a".repeat(40))
    );

    @Mock
    AgentSessionMapper sessionMapper;

    @Mock
    AgentWorkspaceMapper workspaceMapper;

    @Mock
    AgentEventAppender eventAppender;

    @Test
    void shouldCreateSessionWorkspaceAndFirstStepAsOneStoreOperation() {
        CreateSessionCommand command = command("request-1", 11L);
        AtomicReference<AgentSessionEntity> persistedSession = new AtomicReference<>();
        AtomicReference<AgentWorkspaceEntity> persistedWorkspace = new AtomicReference<>();
        when(sessionMapper.claimCreationIdentity(any())).thenAnswer(invocation -> {
            persistedSession.set(invocation.getArgument(0));
            return 1;
        });
        when(sessionMapper.selectForUpdateByCreationIdempotencyKey("request-1"))
                .thenAnswer(invocation -> persistedSession.get());
        when(workspaceMapper.selectBySessionId(command.sessionId()))
                .thenAnswer(invocation -> persistedWorkspace.get());
        when(workspaceMapper.insert(any(AgentWorkspaceEntity.class))).thenAnswer(invocation -> {
            persistedWorkspace.set(invocation.getArgument(0));
            return 1;
        });
        when(eventAppender.append(any())).thenAnswer(invocation -> {
            persistedSession.get().setLastSessionSequence(1L);
            persistedSession.get().setVersion(1L);
            return new AgentEventAppender.AppendResult(1L, 1L, null, false);
        });
        when(sessionMapper.selectById(command.sessionId()))
                .thenAnswer(invocation -> persistedSession.get());

        AgentSessionStore.CreateResult result = store().createProvisioning(command);

        assertTrue(result.created());
        assertEquals(AgentSession.Status.PROVISIONING, result.session().status());
        assertEquals(1L, result.session().lastSessionSequence());
        assertEquals(command.workspaceId(), result.session().workspaceId());
        ArgumentCaptor<AgentEventAppender.AppendCommand> event =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender).append(event.capture());
        assertEquals(AgentStepType.SESSION_CREATED, event.getValue().stepType());
        assertEquals(command.sessionCreatedEventId(), event.getValue().eventId());
        assertEquals(command.workspaceId().toString(), event.getValue().persistedPayload().get("workspaceId").asText());
    }

    @Test
    void shouldResolveARetryToTheSessionAlreadyBoundToTheIdempotencyKey() {
        CreateSessionCommand retry = command("request-1", 11L);
        AgentSessionEntity existingSession = persistedSession(
                "existing-session",
                "request-1",
                11L,
                1L
        );
        AgentWorkspaceEntity existingWorkspace = persistedWorkspace(existingSession.getSessionId());
        when(sessionMapper.selectForUpdateByCreationIdempotencyKey("request-1"))
                .thenReturn(existingSession);
        when(workspaceMapper.selectBySessionId(existingSession.getSessionId()))
                .thenReturn(existingWorkspace);

        AgentSessionStore.CreateResult result = store().createProvisioning(retry);

        assertFalse(result.created());
        assertEquals("existing-session", result.session().sessionId());
        assertEquals(WorkspaceId.parse("00000000-0000-0000-0000-000000000001"), result.session().workspaceId());
        verify(workspaceMapper, never()).insert(any(AgentWorkspaceEntity.class));
        verify(eventAppender, never()).append(any());
    }

    @Test
    void shouldRejectAnIdempotencyKeyBoundToDifferentSemantics() {
        CreateSessionCommand conflicting = command("request-1", 12L);
        AgentSessionEntity existingSession = persistedSession(
                "existing-session",
                "request-1",
                11L,
                1L
        );
        AgentWorkspaceEntity existingWorkspace = persistedWorkspace(existingSession.getSessionId());
        when(sessionMapper.selectForUpdateByCreationIdempotencyKey("request-1"))
                .thenReturn(existingSession);
        when(workspaceMapper.selectBySessionId(existingSession.getSessionId()))
                .thenReturn(existingWorkspace);

        assertThrows(
                IllegalStateException.class,
                () -> store().createProvisioning(conflicting)
        );

        verify(workspaceMapper, never()).insert(any(AgentWorkspaceEntity.class));
        verify(eventAppender, never()).append(any());
    }

    private MyBatisAgentSessionStore store() {
        return new MyBatisAgentSessionStore(
                sessionMapper,
                workspaceMapper,
                eventAppender,
                new ObjectMapper()
        );
    }

    private CreateSessionCommand command(String idempotencyKey, long actorId) {
        return CreateSessionCommand.prepare(
                idempotencyKey,
                actorId,
                REPO_KEY,
                SOURCE
        );
    }

    private AgentSessionEntity persistedSession(
            String sessionId,
            String idempotencyKey,
            long actorId,
            long sequence
    ) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setCreationIdempotencyKey(idempotencyKey);
        session.setCreatedByActorId(actorId);
        session.setRepoId(REPO_KEY.repoId());
        session.setRepoKey(REPO_KEY.value());
        session.setStatus(AgentSession.Status.PROVISIONING.name());
        session.setLastSessionSequence(sequence);
        session.setVersion(sequence);
        session.setCreatedAt(LocalDateTime.parse("2026-08-27T08:00:00"));
        session.setUpdatedAt(LocalDateTime.parse("2026-08-27T08:00:00"));
        return session;
    }

    private AgentWorkspaceEntity persistedWorkspace(String sessionId) {
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setWorkspaceId("00000000-0000-0000-0000-000000000001");
        workspace.setSessionId(sessionId);
        workspace.setBaseRevision(SOURCE.baseSha1().value());
        workspace.setWorkspaceEpoch(0L);
        workspace.setGeneration(0L);
        workspace.setStatus("PROVISIONING");
        workspace.setVersion(0L);
        workspace.setCreatedAt(LocalDateTime.parse("2026-08-27T08:00:00"));
        workspace.setUpdatedAt(LocalDateTime.parse("2026-08-27T08:00:00"));
        return workspace;
    }
}
