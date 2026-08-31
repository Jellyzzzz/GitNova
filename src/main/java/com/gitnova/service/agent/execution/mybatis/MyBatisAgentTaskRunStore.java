package com.gitnova.service.agent.execution.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentTaskEntity;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentRunMapper;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentTaskMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.dispatch.RunDispatchReason;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.execution.AgentTaskRequest;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentOutboxWriter;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.session.AgentSession;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.gitnova.service.agent.execution.AgentExecutionPersistenceException.Code;

/** MySQL implementation of the Task/Run state machine and execution ownership boundary. */
@Repository
public class MyBatisAgentTaskRunStore implements AgentTaskRunStore {
    private final AgentSessionMapper sessionMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunMapper runMapper;
    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentEventAppender eventAppender;
    private final AgentOutboxWriter outboxWriter;
    private final CanonicalJsonCodec canonicalJson;
    private final ObjectMapper objectMapper;

    public MyBatisAgentTaskRunStore(
            AgentSessionMapper sessionMapper,
            AgentTaskMapper taskMapper,
            AgentRunMapper runMapper,
            AgentWorkspaceMapper workspaceMapper,
            AgentEventAppender eventAppender,
            AgentOutboxWriter outboxWriter,
            CanonicalJsonCodec canonicalJson,
            ObjectMapper objectMapper
    ) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper must not be null");
        this.workspaceMapper = Objects.requireNonNull(workspaceMapper, "workspaceMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public CreateResult createTaskWithInitialRun(CreateTaskCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AgentSessionEntity session = requireLockedActiveSession(command.sessionId());
        CanonicalJsonCodec.EncodedJson request = canonicalJson.encode(objectMapper.valueToTree(command.request()));
        ObjectNode executionConfigNode = canonicalJson.objectNode();
        ArrayNode capabilities = executionConfigNode.putArray("capabilities");
        for (AgentCapability capability : AgentCapability.values()) {
            if (command.executionConfig().capabilities().contains(capability)) {
                capabilities.add(capability.name());
            }
        }
        CanonicalJsonCodec.EncodedJson executionConfig = canonicalJson.encode(executionConfigNode);
        LocalDateTime now = utcNow();

        AgentTaskEntity candidate = new AgentTaskEntity();
        candidate.setTaskId(command.taskId());
        candidate.setSessionId(command.sessionId());
        candidate.setCreationIdempotencyKey(command.creationIdempotencyKey());
        candidate.setCreatedByActorId(command.createdByActorId());
        candidate.setStatus(AgentTask.Status.ACTIVE.name());
        candidate.setRequestJson(request.json());
        candidate.setRequestDigest(request.digest());
        candidate.setLastRunNumber(0L);
        candidate.setVersion(0L);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        taskMapper.claimCreationIdentity(candidate);

        AgentTaskEntity task = taskMapper.selectForUpdateByCreationIdentity(
                command.sessionId(),
                command.creationIdempotencyKey()
        );
        if (task == null) {
            throw failure(Code.PERSISTENCE_FAILURE, "Task creation identity could not be claimed");
        }
        if (!command.taskId().equals(task.getTaskId())
                || requireNonNegative(task.getLastRunNumber(), "lastRunNumber") > 0) {
            verifySameTaskRequest(command, request.digest(), executionConfig.digest(), task);
            AgentRunEntity initialRun = requireRun(task.getTaskId(), 1L);
            return new CreateResult(toDomain(task), toDomain(initialRun), false);
        }

        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(session.getSessionId());
        appendTaskEvent(
                command.userMessageEventId(),
                command.sessionId(),
                command.taskId(),
                AgentStepType.USER_MESSAGE_RECEIVED,
                userMessagePayload(command, request),
                null,
                workspace
        );
        appendTaskEvent(
                command.taskCreatedEventId(),
                command.sessionId(),
                command.taskId(),
                AgentStepType.TASK_CREATED,
                taskCreatedPayload(command, request.digest()),
                command.userMessageEventId(),
                workspace
        );

        AgentRunEntity run = newQueuedRun(
                command.initialRunId(),
                command.sessionId(),
                command.taskId(),
                1L,
                null,
                executionConfig,
                now
        );
        if (runMapper.insert(run) != 1) {
            throw failure(Code.PERSISTENCE_FAILURE, "Could not create initial Agent Run");
        }
        if (taskMapper.attachRun(
                command.taskId(),
                command.sessionId(),
                command.initialRunId(),
                0L,
                1L
        ) != 1) {
            throw failure(Code.STATE_CONFLICT, "Could not attach initial Run to Task");
        }
        appendRunEvent(
                command.initialRunQueuedEventId(),
                command.sessionId(),
                command.taskId(),
                command.initialRunId(),
                AgentStepType.RUN_QUEUED,
                runQueuedPayload(run),
                command.taskCreatedEventId(),
                workspace
        );
        enqueueDispatch(
                command.initialDispatchEventId(),
                command.initialRunId(),
                RunDispatchReason.INITIAL,
                null
        );
        return new CreateResult(
                toDomain(requireTask(command.taskId())),
                toDomain(requireRun(command.initialRunId())),
                true
        );
    }

    @Override
    @Transactional
    public ClaimResult claimRun(ClaimCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Global lock order: Session -> Task -> Run -> Workspace.
        requireLockedActiveSession(command.sessionId());
        AgentTaskEntity task = requireLockedTask(command.taskId(), command.sessionId());
        AgentRunEntity run = runMapper.selectForUpdate(command.runId());
        if (run == null) {
            throw failure(Code.UNKNOWN_RUN, "Unknown Run: " + command.runId());
        }
        if (!task.getTaskId().equals(run.getTaskId())
                || !command.sessionId().equals(run.getSessionId())) {
            throw failure(Code.STATE_CONFLICT, "Run does not belong to Task and Session");
        }
        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(command.sessionId());

        if (!AgentTask.Status.ACTIVE.name().equals(task.getStatus())
                || !run.getRunId().equals(task.getCurrentRunId())
                || !"READY".equals(workspace.getStatus())) {
            return new ClaimResult(ClaimDisposition.NOT_CLAIMABLE, null);
        }

        // Redelivery to the current lease owner is an idempotent claim.
        if (AgentRun.Status.RUNNING.name().equals(run.getStatus())
                && command.workerId().equals(run.getLeaseOwner())
                && run.getCurrentFencingToken() != null
                && Objects.equals(run.getCurrentFencingToken(), workspace.getLastAcceptedFencingToken())
                && run.getRunId().equals(workspace.getWriterRunId())
                && runMapper.hasValidLease(
                        run.getRunId(),
                        command.workerId(),
                        run.getCurrentFencingToken()
                ) == 1) {
            return new ClaimResult(ClaimDisposition.ALREADY_CLAIMED, toDomain(run));
        }

        // A fresh claim starts only from QUEUED with no Workspace writer.
        if (!AgentRun.Status.QUEUED.name().equals(run.getStatus())
                || workspace.getWriterRunId() != null) {
            return new ClaimResult(ClaimDisposition.NOT_CLAIMABLE, null);
        }

        long previousFence = requireNonNegative(
                workspace.getLastAcceptedFencingToken(),
                "lastAcceptedFencingToken"
        );
        long nextFence = Math.incrementExact(previousFence);
        if (workspaceMapper.claimWriter(
                workspace.getWorkspaceId(),
                run.getRunId(),
                previousFence,
                nextFence
        ) != 1 || runMapper.claim(
                run.getRunId(),
                command.workerId(),
                nextFence,
                command.leaseSeconds()
        ) != 1) {
            throw failure(Code.STATE_CONFLICT, "Run claim lost its state-machine CAS");
        }

        ObjectNode payload = canonicalJson.objectNode();
        payload.put("fencingToken", nextFence);
        payload.put("runId", run.getRunId());
        payload.put("workerId", command.workerId());
        appendRunEvent(
                command.eventId(),
                command.sessionId(),
                command.taskId(),
                command.runId(),
                AgentStepType.RUN_CLAIMED,
                payload,
                null,
                workspace
        );
        return new ClaimResult(
                ClaimDisposition.CLAIMED,
                toDomain(requireRun(command.runId()))
        );
    }

    @Override
    @Transactional
    public HeartbeatResult heartbeat(HeartbeatCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return runMapper.heartbeat(
                command.runId(),
                command.workerId(),
                command.fencingToken(),
                command.leaseSeconds()
        ) == 1 ? HeartbeatResult.EXTENDED : HeartbeatResult.LEASE_LOST;
    }

    @Override
    @Transactional
    public LeaseExpiryResult recordLeaseExpired(LeaseExpiryCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        requireLockedActiveSession(command.sessionId());
        AgentTaskEntity task = requireLockedTask(command.taskId(), command.sessionId());
        AgentRunEntity run = runMapper.selectExpiredForUpdate(
                command.runId(),
                command.expiredFencingToken()
        );
        if (run == null
                || !task.getTaskId().equals(run.getTaskId())
                || !command.sessionId().equals(run.getSessionId())) {
            return new LeaseExpiryResult(false, null);
        }
        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(command.sessionId());
        if (!run.getRunId().equals(workspace.getWriterRunId())
                || !Objects.equals(run.getCurrentFencingToken(), workspace.getLastAcceptedFencingToken())) {
            throw failure(Code.FENCING_CONFLICT, "Expired Run no longer owns the Workspace");
        }

        ObjectNode payload = canonicalJson.objectNode();
        payload.put("expiredFencingToken", command.expiredFencingToken());
        payload.put("runId", command.runId());
        appendRunEvent(
                command.eventId(),
                command.sessionId(),
                command.taskId(),
                command.runId(),
                AgentStepType.RUN_LEASE_EXPIRED,
                payload,
                null,
                workspace
        );
        enqueueDispatch(
                "run:dispatch:" + command.runId()
                        + ":recovery:" + command.expiredFencingToken(),
                command.runId(),
                RunDispatchReason.RECOVERY,
                command.expiredFencingToken()
        );
        return new LeaseExpiryResult(true, toDomain(requireRun(command.runId())));
    }

    @Override
    @Transactional
    public TakeoverResult takeoverRun(TakeoverCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Global lock order: Session -> Task -> Run -> Workspace.
        requireLockedActiveSession(command.sessionId());
        AgentTaskEntity task = requireLockedTask(command.taskId(), command.sessionId());
        AgentRunEntity run = runMapper.selectForUpdate(command.runId());
        if (run == null) {
            throw failure(Code.UNKNOWN_RUN, "Unknown Run: " + command.runId());
        }
        if (!task.getTaskId().equals(run.getTaskId())
                || !command.sessionId().equals(run.getSessionId())) {
            throw failure(Code.STATE_CONFLICT, "Run does not belong to Task and Session");
        }
        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(command.sessionId());

        if (!AgentTask.Status.ACTIVE.name().equals(task.getStatus())
                || !run.getRunId().equals(task.getCurrentRunId())
                || !"READY".equals(workspace.getStatus())
                || !AgentRun.Status.RUNNING.name().equals(run.getStatus())) {
            return new TakeoverResult(TakeoverDisposition.NOT_ELIGIBLE, null);
        }

        // The same recovery delivery is idempotent after a successful takeover.
        if (run.getCurrentFencingToken() != null
                && run.getCurrentFencingToken() > command.expiredFencingToken()
                && command.workerId().equals(run.getLeaseOwner())
                && Objects.equals(run.getCurrentFencingToken(), workspace.getLastAcceptedFencingToken())
                && run.getRunId().equals(workspace.getWriterRunId())
                && runMapper.hasValidLease(
                        run.getRunId(),
                        command.workerId(),
                        run.getCurrentFencingToken()
                ) == 1) {
            return new TakeoverResult(TakeoverDisposition.ALREADY_TAKEN_OVER, toDomain(run));
        }

        // A real takeover must still own the exact expired fence on Run and Workspace.
        if (!Objects.equals(run.getCurrentFencingToken(), command.expiredFencingToken())
                || !Objects.equals(workspace.getLastAcceptedFencingToken(), command.expiredFencingToken())
                || !run.getRunId().equals(workspace.getWriterRunId())) {
            return new TakeoverResult(TakeoverDisposition.NOT_ELIGIBLE, null);
        }

        long nextFence = Math.incrementExact(command.expiredFencingToken());
        if (workspaceMapper.takeoverWriter(
                workspace.getWorkspaceId(),
                run.getRunId(),
                command.expiredFencingToken(),
                nextFence
        ) != 1 || runMapper.takeover(
                run.getRunId(),
                command.workerId(),
                command.expiredFencingToken(),
                nextFence,
                command.leaseSeconds()
        ) != 1) {
            throw failure(Code.STATE_CONFLICT, "Run takeover lost its lease/fence CAS");
        }

        ObjectNode payload = canonicalJson.objectNode();
        payload.put("expiredFencingToken", command.expiredFencingToken());
        payload.put("fencingToken", nextFence);
        payload.put("runId", command.runId());
        payload.put("workerId", command.workerId());
        appendRunEvent(
                command.eventId(),
                command.sessionId(),
                command.taskId(),
                command.runId(),
                AgentStepType.RUN_TAKEN_OVER,
                payload,
                null,
                workspace
        );
        return new TakeoverResult(
                TakeoverDisposition.TAKEN_OVER,
                toDomain(requireRun(command.runId()))
        );
    }

    @Override
    @Transactional
    public TerminalResult terminateRun(TerminalCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Global lock order: Session -> Task -> Run -> Workspace.
        requireLockedActiveSession(command.sessionId());
        AgentTaskEntity task = requireLockedTask(command.taskId(), command.sessionId());
        AgentRunEntity run = runMapper.selectForUpdate(command.runId());
        if (run == null) {
            throw failure(Code.UNKNOWN_RUN, "Unknown Run: " + command.runId());
        }
        if (!task.getTaskId().equals(run.getTaskId())
                || !command.sessionId().equals(run.getSessionId())) {
            throw failure(Code.STATE_CONFLICT, "Run does not belong to Task and Session");
        }
        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(command.sessionId());

        AgentRun.Status runStatus = AgentRun.Status.valueOf(command.outcome().name());
        AgentTask.Status taskStatus = switch (command.outcome()) {
            case COMPLETED -> AgentTask.Status.COMPLETED;
            case PARTIAL -> AgentTask.Status.WAITING_USER;
            case FAILED -> AgentTask.Status.ACTIVE;
            case CANCELLED -> AgentTask.Status.CANCELLED;
        };
        AgentStepType runStepType = switch (command.outcome()) {
            case COMPLETED -> AgentStepType.RUN_COMPLETED;
            case PARTIAL -> AgentStepType.RUN_PARTIAL;
            case FAILED -> AgentStepType.RUN_FAILED;
            case CANCELLED -> AgentStepType.RUN_CANCELLED;
        };
        AgentStepType taskStepType = switch (command.outcome()) {
            case COMPLETED -> AgentStepType.TASK_COMPLETED;
            case PARTIAL -> AgentStepType.TASK_WAITING_USER;
            case FAILED -> AgentStepType.TASK_RUN_FAILED;
            case CANCELLED -> AgentStepType.TASK_CANCELLED;
        };

        // Retry verifies the committed projection; first delivery performs all three CAS writes.
        boolean terminalRetry = runStatus.name().equals(run.getStatus());
        if (terminalRetry) {
            boolean committedProjectionMatches = taskStatus.name().equals(task.getStatus())
                    && task.getCurrentRunId() == null
                    && workspace.getWriterRunId() == null
                    && Objects.equals(run.getCurrentFencingToken(), command.fencingToken())
                    && Objects.equals(run.getTerminationReason(), command.terminationReason())
                    && Objects.equals(
                            workspace.getLastAcceptedFencingToken(),
                            Math.incrementExact(command.fencingToken())
                    )
                    && (
                            !taskStatus.terminal()
                                    || Objects.equals(
                                            task.getTerminalReason(),
                                            command.terminationReason()
                                    )
                    );
            if (!committedProjectionMatches) {
                throw failure(
                        Code.STATE_CONFLICT,
                        "Terminal Run retry conflicts with Task/Workspace projection"
                );
            }
            // Re-appending the terminal events below verifies the committed event semantics.
        } else {
            if (!AgentTask.Status.ACTIVE.name().equals(task.getStatus())
                    || !run.getRunId().equals(task.getCurrentRunId())
                    || !"READY".equals(workspace.getStatus())
                    || !AgentRun.Status.RUNNING.name().equals(run.getStatus())
                    || !command.workerId().equals(run.getLeaseOwner())
                    || !Objects.equals(run.getCurrentFencingToken(), command.fencingToken())
                    || !command.runId().equals(workspace.getWriterRunId())
                    || !Objects.equals(
                            workspace.getLastAcceptedFencingToken(),
                            command.fencingToken()
                    )) {
                throw failure(
                        Code.LEASE_LOST,
                        "Run no longer owns Task execution and Workspace mutation"
                );
            }

            LocalDateTime taskTerminalAt = taskStatus.terminal() ? utcNow() : null;
            String taskTerminalReason = taskStatus.terminal()
                    ? command.terminationReason()
                    : null;
            if (runMapper.terminate(
                    command.runId(),
                    command.workerId(),
                    command.fencingToken(),
                    runStatus.name(),
                    command.terminationReason()
            ) != 1 || taskMapper.transitionAfterRun(
                    command.taskId(),
                    command.sessionId(),
                    command.runId(),
                    taskStatus.name(),
                    taskTerminalReason,
                    taskTerminalAt
            ) != 1) {
                throw failure(Code.LEASE_LOST, "Run terminal transition lost ownership");
            }

            long revokedFence = Math.incrementExact(command.fencingToken());
            if (workspaceMapper.releaseWriter(
                    workspace.getWorkspaceId(),
                    command.runId(),
                    command.fencingToken(),
                    revokedFence
            ) != 1) {
                throw failure(
                        Code.FENCING_CONFLICT,
                        "Could not revoke Workspace writer on Run termination"
                );
            }
        }

        ObjectNode runPayload = canonicalJson.objectNode();
        runPayload.put("outcome", command.outcome().name());
        runPayload.put("runId", command.runId());
        runPayload.put("taskStatusAfter", taskStatus.name());
        runPayload.put("terminationReason", command.terminationReason());
        appendRunEvent(
                command.runEventId(),
                command.sessionId(),
                command.taskId(),
                command.runId(),
                runStepType,
                runPayload,
                null,
                workspace
        );

        ObjectNode taskPayload = canonicalJson.objectNode();
        taskPayload.put("runId", command.runId());
        taskPayload.put("status", taskStatus.name());
        appendTaskEvent(
                command.taskEventId(),
                command.sessionId(),
                command.taskId(),
                taskStepType,
                taskPayload,
                command.runEventId(),
                workspace
        );

        if (terminalRetry) {
            return new TerminalResult(toDomain(task), toDomain(run));
        }
        return new TerminalResult(
                toDomain(requireTask(command.taskId())),
                toDomain(requireRun(command.runId()))
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTask> findTask(String taskId) {
        requireNonBlank(taskId, "taskId");
        return Optional.ofNullable(taskMapper.selectById(taskId)).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> findRun(String runId) {
        requireNonBlank(runId, "runId");
        return Optional.ofNullable(runMapper.selectById(runId)).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findExpiredRuns(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be in range 1..1000");
        }
        List<AgentRunEntity> runEntities = runMapper.selectExpiredRuns(limit);
        ArrayList<AgentRun> result = new ArrayList<>(runEntities.size());
        for (AgentRunEntity runEntity : runEntities) {
            result.add(toDomain(runEntity));
        }
        return List.copyOf(result);
    }

    private AgentSessionEntity requireLockedActiveSession(String sessionId) {
        AgentSessionEntity session = sessionMapper.selectForUpdate(sessionId);
        if (session == null) {
            throw failure(Code.UNKNOWN_SESSION, "Unknown Session: " + sessionId);
        }
        if (!AgentSession.Status.ACTIVE.name().equals(session.getStatus())) {
            throw failure(Code.STATE_CONFLICT, "Session must be ACTIVE");
        }
        return session;
    }

    private AgentTaskEntity requireLockedTask(String taskId, String sessionId) {
        AgentTaskEntity task = taskMapper.selectForUpdate(taskId);
        if (task == null) {
            throw failure(Code.UNKNOWN_TASK, "Unknown Task: " + taskId);
        }
        if (!sessionId.equals(task.getSessionId())) {
            throw failure(Code.STATE_CONFLICT, "Task does not belong to Session");
        }
        return task;
    }

    private AgentWorkspaceEntity requireLockedReadyWorkspace(String sessionId) {
        AgentWorkspaceEntity workspace = workspaceMapper.selectBySessionId(sessionId);
        if (workspace == null) {
            throw failure(Code.STATE_CONFLICT, "Session has no Logical Workspace");
        }
        AgentWorkspaceEntity locked = workspaceMapper.selectForUpdate(workspace.getWorkspaceId());
        if (locked == null || !"READY".equals(locked.getStatus())) {
            throw failure(Code.STATE_CONFLICT, "Logical Workspace must be READY");
        }
        return locked;
    }

    private void verifySameTaskRequest(
            CreateTaskCommand command,
            String requestDigest,
            String executionConfigDigest,
            AgentTaskEntity task
    ) {
        boolean same = Objects.equals(task.getCreatedByActorId(), command.createdByActorId())
                && Objects.equals(task.getRequestDigest(), requestDigest);
        if (!same) {
            throw failure(Code.IDEMPOTENCY_KEY_CONFLICT,
                    "Task idempotency key is bound to different request semantics");
        }
        AgentRunEntity initialRun = requireRun(task.getTaskId(), 1L);
        if (!Objects.equals(initialRun.getExecutionConfigDigest(), executionConfigDigest)) {
            throw failure(Code.IDEMPOTENCY_KEY_CONFLICT,
                    "Task idempotency key is bound to different execution config");
        }
    }

    private AgentRunEntity newQueuedRun(
            String runId,
            String sessionId,
            String taskId,
            long runNumber,
            String predecessorRunId,
            CanonicalJsonCodec.EncodedJson executionConfig,
            LocalDateTime now
    ) {
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId(runId);
        run.setSessionId(sessionId);
        run.setTaskId(taskId);
        run.setRunNumber(runNumber);
        run.setPredecessorRunId(predecessorRunId);
        run.setStatus(AgentRun.Status.QUEUED.name());
        run.setLastRunStepSequence(0L);
        run.setExecutionConfigJson(executionConfig.json());
        run.setExecutionConfigDigest(executionConfig.digest());
        run.setVersion(0L);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private ObjectNode userMessagePayload(
            CreateTaskCommand command,
            CanonicalJsonCodec.EncodedJson request
    ) {
        ObjectNode payload = canonicalJson.objectNode();
        payload.set("request", objectMapper.valueToTree(command.request()));
        payload.put("requestDigest", request.digest());
        payload.put("taskId", command.taskId());
        return payload;
    }

    private ObjectNode taskCreatedPayload(CreateTaskCommand command, String requestDigest) {
        ObjectNode payload = canonicalJson.objectNode();
        payload.put("creationIdempotencyKey", command.creationIdempotencyKey());
        payload.put("createdByActorId", command.createdByActorId());
        payload.put("requestDigest", requestDigest);
        payload.put("taskId", command.taskId());
        return payload;
    }

    private ObjectNode runQueuedPayload(AgentRunEntity run) {
        ObjectNode payload = canonicalJson.objectNode();
        payload.put("executionConfigDigest", run.getExecutionConfigDigest());
        payload.put("runId", run.getRunId());
        payload.put("runNumber", run.getRunNumber());
        payload.put("taskId", run.getTaskId());
        if (run.getPredecessorRunId() == null) {
            payload.putNull("predecessorRunId");
        } else {
            payload.put("predecessorRunId", run.getPredecessorRunId());
        }
        return payload;
    }

    private void enqueueDispatch(
            String eventId,
            String runId,
            RunDispatchReason reason,
            Long expiredFencingToken
    ) {
        ObjectNode payload = canonicalJson.objectNode();
        payload.put("reason", reason.name());
        payload.put("runId", runId);
        if (expiredFencingToken == null) {
            payload.putNull("expiredFencingToken");
        } else {
            payload.put("expiredFencingToken", expiredFencingToken);
        }
        outboxWriter.enqueue(new AgentOutboxWriter.EnqueueCommand(
                eventId,
                "RUN",
                runId,
                "RUN_DISPATCH_REQUESTED",
                payload,
                Instant.now()
        ));
    }

    private void appendTaskEvent(
            String eventId,
            String sessionId,
            String taskId,
            AgentStepType stepType,
            ObjectNode payload,
            String causationEventId,
            AgentWorkspaceEntity workspace
    ) {
        eventAppender.append(new AgentEventAppender.AppendCommand(
                eventId,
                sessionId,
                taskId,
                null,
                stepType,
                1,
                payload,
                causationEventId,
                taskId,
                workspace.getWorkspaceEpoch(),
                workspace.getGeneration()
        ));
    }

    private void appendRunEvent(
            String eventId,
            String sessionId,
            String taskId,
            String runId,
            AgentStepType stepType,
            ObjectNode payload,
            String causationEventId,
            AgentWorkspaceEntity workspace
    ) {
        eventAppender.append(new AgentEventAppender.AppendCommand(
                eventId,
                sessionId,
                taskId,
                runId,
                stepType,
                1,
                payload,
                causationEventId,
                taskId,
                workspace.getWorkspaceEpoch(),
                workspace.getGeneration()
        ));
    }

    private AgentTaskEntity requireTask(String taskId) {
        AgentTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw failure(Code.UNKNOWN_TASK, "Unknown Task: " + taskId);
        }
        return task;
    }

    private AgentRunEntity requireRun(String runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw failure(Code.UNKNOWN_RUN, "Unknown Run: " + runId);
        }
        return run;
    }

    private AgentRunEntity requireRun(String taskId, long runNumber) {
        AgentRunEntity run = runMapper.selectByTaskAndRunNumber(taskId, runNumber);
        if (run == null) {
            throw failure(Code.PERSISTENCE_FAILURE, "Task has no Run number " + runNumber);
        }
        return run;
    }

    private AgentTask toDomain(AgentTaskEntity task) {
        return new AgentTask(
                task.getTaskId(),
                task.getSessionId(),
                task.getCreationIdempotencyKey(),
                requireLong(task.getCreatedByActorId(), "createdByActorId"),
                AgentTask.Status.valueOf(task.getStatus()),
                decodeTaskRequest(task.getRequestJson()),
                task.getRequestDigest(),
                task.getCurrentRunId(),
                requireLong(task.getLastRunNumber(), "lastRunNumber"),
                task.getTerminalReason(),
                requireLong(task.getVersion(), "version"),
                requireTime(task.getCreatedAt(), "createdAt"),
                requireTime(task.getUpdatedAt(), "updatedAt"),
                optionalTime(task.getTerminalAt())
        );
    }

    private AgentTaskRequest decodeTaskRequest(String requestJson) {
        try {
            return objectMapper.readValue(requestJson, AgentTaskRequest.class);
        } catch (JsonProcessingException exception) {
            throw failure(Code.PERSISTENCE_FAILURE, "Task request JSON is invalid");
        }
    }

    private AgentRun toDomain(AgentRunEntity run) {
        return new AgentRun(
                run.getRunId(),
                run.getSessionId(),
                run.getTaskId(),
                requireLong(run.getRunNumber(), "runNumber"),
                run.getPredecessorRunId(),
                AgentRun.Status.valueOf(run.getStatus()),
                requireLong(run.getLastRunStepSequence(), "lastRunStepSequence"),
                run.getLeaseOwner(),
                optionalTime(run.getLeaseUntil()),
                run.getCurrentFencingToken(),
                decodeExecutionConfig(run.getExecutionConfigJson()),
                run.getExecutionConfigDigest(),
                run.getTerminationReason(),
                requireLong(run.getVersion(), "version"),
                requireTime(run.getCreatedAt(), "createdAt"),
                optionalTime(run.getClaimedAt()),
                optionalTime(run.getLastHeartbeatAt()),
                optionalTime(run.getFinishedAt()),
                requireTime(run.getUpdatedAt(), "updatedAt")
        );
    }

    private AgentExecutionConfig decodeExecutionConfig(String executionConfigJson) {
        try {
            return objectMapper.readValue(executionConfigJson, AgentExecutionConfig.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(Code.PERSISTENCE_FAILURE, "Run execution config JSON is invalid");
        }
    }

    private static long requireNonNegative(Long value, String field) {
        long actual = requireLong(value, field);
        if (actual < 0) {
            throw failure(Code.PERSISTENCE_FAILURE, field + " must not be negative");
        }
        return actual;
    }

    private static long requireLong(Long value, String field) {
        if (value == null) {
            throw failure(Code.PERSISTENCE_FAILURE, field + " must be persisted");
        }
        return value;
    }

    private static Instant requireTime(LocalDateTime value, String field) {
        if (value == null) {
            throw failure(Code.PERSISTENCE_FAILURE, field + " must be persisted");
        }
        return value.toInstant(ZoneOffset.UTC);
    }

    private static Instant optionalTime(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static AgentExecutionPersistenceException failure(Code code, String message) {
        return new AgentExecutionPersistenceException(code, message);
    }

}
