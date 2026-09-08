package com.gitnova.controller;

import com.gitnova.common.UserContext;
import com.gitnova.config.GlobalExceptionHandler;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.Repository;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.mapper.BranchMapper;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.service.session.RepositoryRevisionService;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSessionControllerTest {

    private static final RepoKey REPO_KEY = RepoKey.of(7L, 42L);
    private static final SnapshotScope SOURCE = new SnapshotScope(
            GitObjectId.of("a".repeat(40))
    );

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void shouldBuildTheTrustedCommandAfterRepositoryAuthorization() throws Exception {
        Dependencies dependencies = dependencies();
        Repository repository = repository();
        AgentSession active = session(
                "session-1",
                "request-1",
                11L,
                REPO_KEY,
                AgentSession.Status.ACTIVE
        );
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenReturn(repository);
        when(dependencies.agentSessionService.findByCreationIdempotencyKey("request-1"))
                .thenReturn(Optional.empty());
        when(dependencies.repositoryRevisionService.requireBranchSnapshot(
                42L,
                "feature/session"
        )).thenReturn(SOURCE);
        when(dependencies.agentSessionService.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn(active);

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(dependencies.controller)
                .build();
        mockMvc.perform(post("/api/repos/42/agent/sessions")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchName\":\"feature/session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("session-1"))
                .andExpect(jsonPath("$.data.workspaceId").value(
                        "00000000-0000-0000-0000-000000000001"
                ))
                .andExpect(jsonPath("$.data.baseRevision").value(
                        SOURCE.baseSha1().value()
                ));

        assertEquals(REPO_KEY.repoId(), repository.getId());
        ArgumentCaptor<CreateSessionCommand> command = ArgumentCaptor.forClass(
                CreateSessionCommand.class
        );
        verify(dependencies.agentSessionService).create(command.capture());
        assertEquals("request-1", command.getValue().creationIdempotencyKey());
        assertEquals(11L, command.getValue().createdByActorId());
        assertEquals(REPO_KEY, command.getValue().repoKey());
        assertEquals(SOURCE, command.getValue().source());

        InOrder order = inOrder(
                dependencies.repositoryAccessService,
                dependencies.agentSessionService,
                dependencies.repositoryRevisionService
        );
        order.verify(dependencies.repositoryAccessService).requireReadAccess(42L, 11L);
        order.verify(dependencies.agentSessionService)
                .findByCreationIdempotencyKey("request-1");
        order.verify(dependencies.repositoryRevisionService)
                .requireBranchSnapshot(42L, "feature/session");
        order.verify(dependencies.agentSessionService).create(command.getValue());
    }

    @Test
    void shouldReturnTheOriginalSessionForAnIdempotentRetry() {
        Dependencies dependencies = dependencies();
        AgentSession existing = session(
                "existing-session",
                "request-1",
                11L,
                REPO_KEY,
                AgentSession.Status.ACTIVE
        );
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenReturn(repository());
        when(dependencies.agentSessionService.findByCreationIdempotencyKey("request-1"))
                .thenReturn(Optional.of(existing));

        ApiResponse<AgentSessionController.SessionResponse> response =
                dependencies.controller.create(
                        42L,
                        "request-1",
                        new AgentSessionController.CreateSessionRequest("main")
                );

        assertEquals("existing-session", response.getData().sessionId());
        verifyNoInteractions(dependencies.repositoryRevisionService);
        verify(dependencies.agentSessionService, never())
                .create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldResumeRetryableWorkspaceProvisioningFromPersistedSemantics() {
        Dependencies dependencies = dependencies();
        AgentSession provisioning = session(
                "existing-session",
                "request-1",
                11L,
                REPO_KEY,
                AgentSession.Status.PROVISIONING
        );
        AgentSession active = session(
                provisioning.sessionId(),
                provisioning.creationIdempotencyKey(),
                provisioning.createdByActorId(),
                provisioning.repoKey(),
                AgentSession.Status.ACTIVE
        );
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenReturn(repository());
        when(dependencies.agentSessionService.findByCreationIdempotencyKey("request-1"))
                .thenReturn(Optional.of(provisioning));
        when(dependencies.agentSessionService.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn(active);

        ApiResponse<AgentSessionController.SessionResponse> response =
                dependencies.controller.create(
                        42L,
                        "request-1",
                        new AgentSessionController.CreateSessionRequest("other-branch")
                );

        assertEquals("ACTIVE", response.getData().status());
        ArgumentCaptor<CreateSessionCommand> command = ArgumentCaptor.forClass(
                CreateSessionCommand.class
        );
        verify(dependencies.agentSessionService).create(command.capture());
        assertEquals(provisioning.sessionId(), command.getValue().sessionId());
        assertEquals(provisioning.workspaceId(), command.getValue().workspaceId());
        assertEquals(provisioning.source(), command.getValue().source());
        verifyNoInteractions(dependencies.repositoryRevisionService);
    }

    @Test
    void shouldNotResolveARevisionWhenRepositoryAccessIsDenied() {
        Dependencies dependencies = dependencies();
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenThrow(new IllegalStateException("denied"));

        assertThrows(
                IllegalStateException.class,
                () -> dependencies.controller.create(
                        42L,
                        "request-1",
                        new AgentSessionController.CreateSessionRequest("main")
                )
        );

        verifyNoInteractions(dependencies.repositoryRevisionService);
        verifyNoInteractions(dependencies.agentSessionService);
    }

    @Test
    void shouldRejectAnIdempotencyKeyOwnedByAnotherCreationScope() {
        Dependencies dependencies = dependencies();
        AgentSession otherActor = session(
                "other-session",
                "request-1",
                99L,
                REPO_KEY,
                AgentSession.Status.ACTIVE
        );
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenReturn(repository());
        when(dependencies.agentSessionService.findByCreationIdempotencyKey("request-1"))
                .thenReturn(Optional.of(otherActor));

        assertThrows(
                AgentSessionStore.CreationConflictException.class,
                () -> dependencies.controller.create(
                        42L,
                        "request-1",
                        new AgentSessionController.CreateSessionRequest("main")
                )
        );

        verifyNoInteractions(dependencies.repositoryRevisionService);
    }

    @Test
    void shouldReturnHttp404WhenTheSelectedBranchDoesNotExist() throws Exception {
        Dependencies dependencies = dependencies();
        RepositoryRevisionService.BranchNotFoundException missingBranch =
                branchNotFound("missing");
        UserContext.setUserId(11L);
        when(dependencies.repositoryAccessService.requireReadAccess(42L, 11L))
                .thenReturn(repository());
        when(dependencies.agentSessionService.findByCreationIdempotencyKey("request-1"))
                .thenReturn(Optional.empty());
        when(dependencies.repositoryRevisionService.requireBranchSnapshot(42L, "missing"))
                .thenThrow(missingBranch);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(dependencies.controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/repos/42/agent/sessions")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchName\":\"missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(dependencies.agentSessionService, never())
                .create(org.mockito.ArgumentMatchers.any());
    }

    private static RepositoryRevisionService.BranchNotFoundException branchNotFound(
            String branchName
    ) {
        BranchMapper branchMapper = mock(BranchMapper.class);
        when(branchMapper.findHead(42L, branchName)).thenReturn(null);
        return assertThrows(
                RepositoryRevisionService.BranchNotFoundException.class,
                () -> new RepositoryRevisionService(branchMapper)
                        .requireBranchSnapshot(42L, branchName)
        );
    }

    private static Dependencies dependencies() {
        RepositoryAccessService accessService = mock(RepositoryAccessService.class);
        RepositoryRevisionService revisionService = mock(RepositoryRevisionService.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        return new Dependencies(
                accessService,
                revisionService,
                sessionService,
                new AgentSessionController(accessService, revisionService, sessionService)
        );
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(42L);
        repository.setOwnerId(7L);
        repository.setIsPrivate(1);
        return repository;
    }

    private static AgentSession session(
            String sessionId,
            String idempotencyKey,
            long actorId,
            RepoKey repoKey,
            AgentSession.Status status
    ) {
        Instant now = Instant.parse("2026-09-06T08:00:00Z");
        return new AgentSession(
                sessionId,
                idempotencyKey,
                actorId,
                repoKey,
                WorkspaceId.parse("00000000-0000-0000-0000-000000000001"),
                SOURCE,
                status,
                2L,
                1L,
                now,
                now
        );
    }

    private record Dependencies(
            RepositoryAccessService repositoryAccessService,
            RepositoryRevisionService repositoryRevisionService,
            AgentSessionService agentSessionService,
            AgentSessionController controller
    ) {
    }
}
