package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.service.session.CreateSessionCommand;
import com.gitnova.storage.RepoKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MyBatis adapter for the transactional Session/Logical Workspace aggregate. */
@Repository
public class MyBatisAgentSessionStore implements AgentSessionStore {

    private final AgentSessionMapper sessionMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentEventAppender eventAppender;
    private final ObjectMapper objectMapper;

    public MyBatisAgentSessionStore(
            AgentSessionMapper sessionMapper,
            AgentWorkspaceMapper workspaceMapper,
            AgentEventAppender eventAppender,
            ObjectMapper objectMapper
    ) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper must not be null");
        this.workspaceMapper = Objects.requireNonNull(workspaceMapper, "workspaceMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public CreateResult createProvisioning(CreateSessionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(command.sessionId());
        session.setCreationIdempotencyKey(command.creationIdempotencyKey());
        session.setCreatedByActorId(command.createdByActorId());
        session.setRepoId(command.repoKey().repoId());
        session.setRepoKey(command.repoKey().value());
        session.setStatus(AgentSession.Status.PROVISIONING.name());
        session.setLastSessionSequence(0L);
        session.setVersion(0L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.claimCreationIdentity(session);
        AgentSessionEntity claimed = sessionMapper.selectForUpdateByCreationIdempotencyKey(
                command.creationIdempotencyKey()
        );
        if (claimed == null) {
            throw new CreationConflictException(
                    "Session identity conflicts with another creation request"
            );
        }
        AgentWorkspaceEntity existingWorkspace = workspaceMapper.selectBySessionId(
                claimed.getSessionId()
        );
        if (existingWorkspace != null) {
            verifySameCreateRequest(command, claimed, existingWorkspace);
            return CreateResult.alreadyExisting(toDomain(claimed, existingWorkspace));
        }
        if (!command.sessionId().equals(claimed.getSessionId())) {
            throw new CreationConflictException(
                    "Session identity conflicts with another creation request"
            );
        }

        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setWorkspaceId(command.workspaceId().toString());
        workspace.setSessionId(command.sessionId());
        workspace.setBaseRevision(command.source().baseSha1().value());
        workspace.setWorkspaceEpoch(0L);
        workspace.setGeneration(0L);
        workspace.setStatus("PROVISIONING");
        workspace.setLastAcceptedFencingToken(0L);
        workspace.setVersion(0L);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        if (workspaceMapper.insert(workspace) != 1) {
            throw new IllegalStateException("Could not create Logical Workspace projection");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("createdByActorId", command.createdByActorId());
        payload.put("repoKey", command.repoKey().value());
        payload.put("workspaceId", command.workspaceId().toString());
        payload.put("baseRevision", command.source().baseSha1().value());
        eventAppender.append(AgentEventAppender.AppendCommand.sessionEvent(
                command.sessionCreatedEventId(),
                command.sessionId(),
                AgentStepType.SESSION_CREATED,
                payload,
                0L,
                0L
        ));
        return CreateResult.created(requireAggregate(command.sessionId()));
    }

    @Override
    @Transactional
    public AgentSession activate(WorkspaceActivation activation) {
        Objects.requireNonNull(activation, "activation must not be null");
        AgentSessionEntity session = requireLockedSession(activation.sessionId());
        AgentWorkspaceEntity workspace = requireLockedWorkspace(session.getSessionId());

        if (AgentSession.Status.ACTIVE.name().equals(session.getStatus())
                && "READY".equals(workspace.getStatus())) {
            verifySameActivation(activation, workspace);
            return toDomain(session, workspace);
        }
        requireStatus(session.getStatus(), AgentSession.Status.PROVISIONING.name(), "Session");
        requireStatus(workspace.getStatus(), "PROVISIONING", "Workspace");

        if (workspaceMapper.activate(
                workspace.getWorkspaceId(),
                activation.providerType(),
                activation.providerRef(),
                activation.manifestDigest(),
                activation.fingerprint()
        ) != 1) {
            throw new IllegalStateException("Could not activate Logical Workspace");
        }
        if (sessionMapper.activate(session.getSessionId()) != 1) {
            throw new IllegalStateException("Could not activate Agent Session");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workspaceId", workspace.getWorkspaceId());
        payload.put("providerType", activation.providerType());
        payload.put("providerRef", activation.providerRef());
        if (activation.manifestDigest() == null) {
            payload.putNull("manifestDigest");
        } else {
            payload.put("manifestDigest", activation.manifestDigest());
        }
        payload.put("fingerprint", activation.fingerprint());
        eventAppender.append(AgentEventAppender.AppendCommand.sessionEvent(
                activation.eventId(),
                session.getSessionId(),
                AgentStepType.WORKSPACE_MATERIALIZED,
                payload,
                workspace.getWorkspaceEpoch(),
                workspace.getGeneration()
        ));
        return requireAggregate(session.getSessionId());
    }

    @Override
    @Transactional
    public AgentSession failProvisioning(ProvisioningFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        AgentSessionEntity session = requireLockedSession(failure.sessionId());
        AgentWorkspaceEntity workspace = requireLockedWorkspace(session.getSessionId());

        if (!failure.retryable()) {
            if (AgentSession.Status.FAILED.name().equals(session.getStatus())
                    && "FAILED".equals(workspace.getStatus())) {
                return toDomain(session, workspace);
            }
            requireStatus(session.getStatus(), AgentSession.Status.PROVISIONING.name(), "Session");
            requireStatus(workspace.getStatus(), "PROVISIONING", "Workspace");
            if (workspaceMapper.failProvisioning(workspace.getWorkspaceId()) != 1
                    || sessionMapper.failProvisioning(session.getSessionId()) != 1) {
                throw new IllegalStateException("Could not fail Session provisioning");
            }
        } else {
            requireStatus(session.getStatus(), AgentSession.Status.PROVISIONING.name(), "Session");
            requireStatus(workspace.getStatus(), "PROVISIONING", "Workspace");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workspaceId", workspace.getWorkspaceId());
        payload.put("reasonCode", failure.reasonCode());
        payload.put("safeMessage", failure.safeMessage());
        payload.put("retryable", failure.retryable());
        eventAppender.append(AgentEventAppender.AppendCommand.sessionEvent(
                failure.eventId(),
                session.getSessionId(),
                AgentStepType.WORKSPACE_PROVISIONING_FAILED,
                payload,
                workspace.getWorkspaceEpoch(),
                workspace.getGeneration()
        ));
        return requireAggregate(session.getSessionId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentSession> findById(String sessionId) {
        requireNonBlank(sessionId, "sessionId");
        AgentSessionEntity session = sessionMapper.selectById(sessionId);
        return session == null
                ? Optional.empty()
                : Optional.of(toDomain(session, requireWorkspace(sessionId)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWorkspaceState(WorkspaceStateChange change) {
        Objects.requireNonNull(change, "change must not be null");
        // Same Session -> Workspace lock order as creation/activation. No filesystem work in this transaction.
        AgentSessionEntity session = requireLockedSession(change.sessionId());
        AgentWorkspaceEntity workspace = requireLockedWorkspace(session.getSessionId());
        if (!"ACTIVE".equals(session.getStatus()) || !"READY".equals(workspace.getStatus())
                || !change.workspaceId().equals(workspace.getWorkspaceId())
                || !Objects.equals(change.epoch(), workspace.getWorkspaceEpoch())) {
            throw new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Workspace identity or lifecycle changed before state persistence");
        }
        var permit = change.executionPermit();
        if (permit != null && (!permit.runId().equals(workspace.getWriterRunId())
                || permit.fencingToken() != workspace.getLastAcceptedFencingToken())) {
            throw new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.LEASE_LOST,
                    "Workspace writer was superseded before state persistence");
        }
        // A committed response may have been lost. Return without appending a second logical event.
        if (workspace.getGeneration() == change.generationAfter()
                && Objects.equals(workspace.getContentFingerprint(), change.fingerprintAfter())) return;
        if (workspaceMapper.updateObservedState(change.workspaceId(), change.epoch(),
                change.generationBefore(), change.fingerprintBefore(),
                change.generationAfter(), change.fingerprintAfter()) != 1) {
            throw new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Persisted Workspace state changed; stale state cannot overwrite it");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("workspaceId", change.workspaceId());
        payload.put("generationBefore", change.generationBefore());
        payload.put("generationAfter", change.generationAfter());
        payload.put("fingerprintBefore", change.fingerprintBefore());
        payload.put("fingerprintAfter", change.fingerprintAfter());
        payload.put("stateVerified", change.fingerprintAfter() != null);
        if (permit != null) {
            payload.put("observedByRunId", permit.runId());
            payload.put("fencingToken", permit.fencingToken());
        }
        String eventId = "workspace:" + change.workspaceId() + ":state:" + change.epoch()
                + ":" + change.generationAfter() + ":"
                + Objects.requireNonNullElse(change.fingerprintAfter(), "unverified");
        eventAppender.append(AgentEventAppender.AppendCommand.sessionEvent(eventId, change.sessionId(),
                AgentStepType.WORKSPACE_STATE_OBSERVED, payload, change.epoch(), change.generationAfter()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentSession> findByCreationIdempotencyKey(String creationIdempotencyKey) {
        requireNonBlank(creationIdempotencyKey, "creationIdempotencyKey");
        AgentSessionEntity session = sessionMapper.selectByCreationIdempotencyKey(
                creationIdempotencyKey
        );
        return session == null
                ? Optional.empty()
                : Optional.of(toDomain(session, requireWorkspace(session.getSessionId())));
    }

    @Transactional(readOnly = true)
    @Override
    public List<AgentSession> selectByRepositoryAndCreator(Long repoId, Long actorId, int limit) {
        List<AgentSessionEntity> entries = sessionMapper.selectByRepositoryAndCreator(repoId, actorId, limit);
        List<AgentSession> sessions = new ArrayList<>();
        for (AgentSessionEntity entry : entries) {
            sessions.add(toDomain(entry, requireWorkspace(entry.getSessionId())));
        }
        return List.copyOf(sessions);
    }

    private AgentSession requireAggregate(String sessionId) {
        AgentSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalStateException("Session disappeared during its transaction: " + sessionId);
        }
        return toDomain(session, requireWorkspace(sessionId));
    }

    private AgentSessionEntity requireLockedSession(String sessionId) {
        AgentSessionEntity session = sessionMapper.selectForUpdate(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown Session: " + sessionId);
        }
        return session;
    }

    private AgentWorkspaceEntity requireLockedWorkspace(String sessionId) {
        AgentWorkspaceEntity workspace = requireWorkspace(sessionId);
        AgentWorkspaceEntity locked = workspaceMapper.selectForUpdate(workspace.getWorkspaceId());
        if (locked == null) {
            throw new IllegalStateException("Logical Workspace disappeared: " + workspace.getWorkspaceId());
        }
        return locked;
    }

    private AgentWorkspaceEntity requireWorkspace(String sessionId) {
        AgentWorkspaceEntity workspace = workspaceMapper.selectBySessionId(sessionId);
        if (workspace == null) {
            throw new IllegalStateException("Session has no Logical Workspace: " + sessionId);
        }
        return workspace;
    }

    private void verifySameCreateRequest(
            CreateSessionCommand command,
            AgentSessionEntity session,
            AgentWorkspaceEntity workspace
    ) {
        boolean same = Objects.equals(session.getCreatedByActorId(), command.createdByActorId())
                && Objects.equals(session.getRepoId(), command.repoKey().repoId())
                && Objects.equals(session.getRepoKey(), command.repoKey().value())
                && Objects.equals(workspace.getBaseRevision(), command.source().baseSha1().value());
        if (!same) {
            throw new CreationConflictException(
                    "creationIdempotencyKey is already bound to different Session semantics"
            );
        }
    }

    private static void verifySameActivation(
            WorkspaceActivation activation,
            AgentWorkspaceEntity workspace
    ) {
        boolean same = Objects.equals(workspace.getProviderType(), activation.providerType())
                && Objects.equals(workspace.getProviderRef(), activation.providerRef())
                && Objects.equals(workspace.getManifestDigest(), activation.manifestDigest())
                && Objects.equals(workspace.getContentFingerprint(), activation.fingerprint());
        if (!same) {
            throw new IllegalStateException("Workspace is already active with different provider metadata");
        }
    }

    private AgentSession toDomain(
            AgentSessionEntity session,
            AgentWorkspaceEntity workspace
    ) {
        return new AgentSession(
                session.getSessionId(),
                session.getCreationIdempotencyKey(),
                session.getCreatedByActorId(),
                RepoKey.parseCanonical(session.getRepoKey()),
                WorkspaceId.parse(workspace.getWorkspaceId()),
                SnapshotScope.of(workspace.getBaseRevision()),
                AgentSession.Status.valueOf(session.getStatus()),
                session.getLastSessionSequence(),
                session.getVersion(),
                requireTime(session.getCreatedAt(), "createdAt"),
                requireTime(session.getUpdatedAt(), "updatedAt")
        );
    }

    private static Instant requireTime(LocalDateTime value, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " must be persisted");
        }
        return value.toInstant(ZoneOffset.UTC);
    }

    private static void requireStatus(String actual, String expected, String aggregate) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    aggregate + " must be " + expected + " but was " + actual
            );
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
