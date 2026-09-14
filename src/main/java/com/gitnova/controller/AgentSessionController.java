package com.gitnova.controller;

import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.entity.Repository;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionService;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.service.session.RepositoryRevisionService;
import com.gitnova.storage.RepoKey;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** HTTP application adapter for creating durable Agent Sessions. */
@RestController
@RequestMapping("/api/repos/{repoId}/agent/sessions")
public class AgentSessionController {

    private final RepositoryAccessService repositoryAccessService;
    private final RepositoryRevisionService repositoryRevisionService;
    private final AgentSessionService agentSessionService;

    public AgentSessionController(
            RepositoryAccessService repositoryAccessService,
            RepositoryRevisionService repositoryRevisionService,
            AgentSessionService agentSessionService
    ) {
        this.repositoryAccessService = Objects.requireNonNull(
                repositoryAccessService,
                "repositoryAccessService must not be null"
        );
        this.repositoryRevisionService = Objects.requireNonNull(
                repositoryRevisionService,
                "repositoryRevisionService must not be null"
        );
        this.agentSessionService = Objects.requireNonNull(
                agentSessionService,
                "agentSessionService must not be null"
        );
    }

    @PostMapping
    public ApiResponse<SessionResponse> create(
            @PathVariable Long repoId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) CreateSessionRequest request
    ) {
        Long actorId = UserContext.getUserId();
        if (actorId == null || actorId <= 0) {
            throw new IllegalStateException("Authenticated actor is missing");
        }
        String creationKey = CreateSessionCommand.requireValidIdempotencyKey(
                idempotencyKey
        );

        Repository repository = repositoryAccessService.requireReadAccess(repoId, actorId);
        RepoKey repoKey = RepoKey.of(repository.getOwnerId(), repository.getId());
        Optional<AgentSession> existing = agentSessionService
                .findByCreationIdempotencyKey(creationKey);
        if (existing.isPresent()) {
            return ApiResponse.success(SessionResponse.from(resumeExisting(
                    existing.orElseThrow(),
                    actorId,
                    repoKey
            )));
        }

        SnapshotScope source = repositoryRevisionService.requireBranchSnapshot(
                repository.getId(),
                request == null ? null : request.branchName()
        );
        AgentSession session;
        try {
            session = agentSessionService.create(CreateSessionCommand.prepare(
                    creationKey,
                    actorId,
                    repoKey,
                    source
            ));
        } catch (AgentSessionStore.CreationConflictException conflict) {
            AgentSession winner = agentSessionService
                    .findByCreationIdempotencyKey(creationKey)
                    .orElseThrow(() -> conflict);
            session = resumeExisting(winner, actorId, repoKey);
        }
        return ApiResponse.success(SessionResponse.from(session));
    }

    @GetMapping
    public ApiResponse<List<SessionResponse>> listSessions(
            @PathVariable Long repoId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        Long actorId = UserContext.getUserId();
        if (actorId == null || actorId <= 0) {
            throw new IllegalStateException("Authenticated actor is missing");
        }
        repositoryAccessService.requireReadAccess(repoId, actorId);
        List<AgentSession> sessions = agentSessionService.listSessions(repoId, actorId, limit);
        List<SessionResponse> responses = new ArrayList<>();
        for (AgentSession session : sessions) {
            responses.add(SessionResponse.from(session));
        }
        return ApiResponse.success(List.copyOf(responses));
    }

    private AgentSession resumeExisting(
            AgentSession session,
            long actorId,
            RepoKey repoKey
    ) {
        requireSameCreationScope(session, actorId, repoKey);
        if (session.status() != AgentSession.Status.PROVISIONING) {
            return session;
        }
        return agentSessionService.create(new CreateSessionCommand(
                session.creationIdempotencyKey(),
                session.sessionId(),
                session.workspaceId(),
                session.createdByActorId(),
                session.repoKey(),
                session.source()
        ));
    }

    private static void requireSameCreationScope(
            AgentSession session,
            long actorId,
            RepoKey repoKey
    ) {
        if (session.createdByActorId() != actorId || !session.repoKey().equals(repoKey)) {
            throw new AgentSessionStore.CreationConflictException(
                    "Idempotency-Key is already bound to a different Session creation"
            );
        }
    }

    public record CreateSessionRequest(String branchName) {
    }

    public record SessionResponse(
            String sessionId,
            String workspaceId,
            String status,
            String baseRevision,
            long lastSessionSequence,
            Instant createdAt,
            Instant updatedAt
    ) {
        private static SessionResponse from(AgentSession session) {
            return new SessionResponse(
                    session.sessionId(),
                    session.workspaceId().toString(),
                    session.status().name(),
                    session.source().baseSha1().value(),
                    session.lastSessionSequence(),
                    session.createdAt(),
                    session.updatedAt()
            );
        }
    }
}
