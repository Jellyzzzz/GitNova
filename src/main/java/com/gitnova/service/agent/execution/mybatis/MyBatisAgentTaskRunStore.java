package com.gitnova.service.agent.execution.mybatis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentTaskEntity;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentRunMapper;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentTaskMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentOutboxWriter;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.session.AgentSession;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    public MyBatisAgentTaskRunStore(
            AgentSessionMapper sessionMapper,
            AgentTaskMapper taskMapper,
            AgentRunMapper runMapper,
            AgentWorkspaceMapper workspaceMapper,
            AgentEventAppender eventAppender,
            AgentOutboxWriter outboxWriter,
            CanonicalJsonCodec canonicalJson
    ) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper must not be null");
        this.workspaceMapper = Objects.requireNonNull(workspaceMapper, "workspaceMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson must not be null");
    }

    @Override
    @Transactional
    public CreateResult createTaskWithInitialRun(CreateTaskCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AgentSessionEntity session = requireLockedActiveSession(command.sessionId());
        CanonicalJsonCodec.EncodedJson request = canonicalJson.encode(command.request());
        CanonicalJsonCodec.EncodedJson executionConfig = canonicalJson.encode(command.executionConfig());
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
                "INITIAL",
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
        LockedExecution locked = lockExecution(command.sessionId(), command.taskId(), command.runId());
        if (!eligibleTaskAndWorkspace(locked)) {
            return new ClaimResult(ClaimDisposition.NOT_CLAIMABLE, null);
        }
        AgentRunEntity run = locked.run();
        AgentWorkspaceEntity workspace = locked.workspace();

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
        if (run == null || !belongsTo(run, task, command.sessionId())) {
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
                recoveryDispatchEventId(command.runId(), command.expiredFencingToken()),
                command.runId(),
                "RECOVERY",
                command.expiredFencingToken()
        );
        return new LeaseExpiryResult(true, toDomain(requireRun(command.runId())));
    }

    @Override
    @Transactional
    public TakeoverResult takeoverRun(TakeoverCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        LockedExecution locked = lockExecution(command.sessionId(), command.taskId(), command.runId());
        if (!eligibleTaskAndWorkspace(locked)
                || !AgentRun.Status.RUNNING.name().equals(locked.run().getStatus())) {
            return new TakeoverResult(TakeoverDisposition.NOT_ELIGIBLE, null);
        }
        AgentRunEntity run = locked.run();
        AgentWorkspaceEntity workspace = locked.workspace();

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
        LockedExecution locked = lockExecution(command.sessionId(), command.taskId(), command.runId());
        AgentTask.Status taskStatus = taskStatus(command.outcome());
        AgentRun.Status runStatus = AgentRun.Status.valueOf(command.outcome().name());

        if (runStatus.name().equals(locked.run().getStatus())) {
            if (taskStatus.name().equals(locked.task().getStatus())
                    && locked.task().getCurrentRunId() == null
                    && locked.workspace().getWriterRunId() == null
                    && Objects.equals(locked.run().getCurrentFencingToken(), command.fencingToken())
                    && Objects.equals(locked.run().getTerminationReason(), command.terminationReason())
                    && Objects.equals(
                            locked.workspace().getLastAcceptedFencingToken(),
                            Math.incrementExact(command.fencingToken())
                    )
                    && (
                            !taskStatus.terminal()
                                    || Objects.equals(
                                            locked.task().getTerminalReason(),
                                            command.terminationReason()
                                    )
                    )) {
                appendTerminalEvents(command, taskStatus, locked.workspace());
                return new TerminalResult(toDomain(locked.task()), toDomain(locked.run()));
            }
            throw failure(Code.STATE_CONFLICT, "Terminal Run retry conflicts with Task/Workspace projection");
        }
        requireTerminalOwnership(command, locked);

        LocalDateTime taskTerminalAt = taskStatus.terminal() ? utcNow() : null;
        String taskTerminalReason = taskStatus.terminal() ? command.terminationReason() : null;
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
                locked.workspace().getWorkspaceId(),
                command.runId(),
                command.fencingToken(),
                revokedFence
        ) != 1) {
            throw failure(Code.FENCING_CONFLICT, "Could not revoke Workspace writer on Run termination");
        }

        appendTerminalEvents(command, taskStatus, locked.workspace());
        return new TerminalResult(
                toDomain(requireTask(command.taskId())),
                toDomain(requireRun(command.runId()))
        );
    }

    private void appendTerminalEvents(
            TerminalCommand command,
            AgentTask.Status taskStatus,
            AgentWorkspaceEntity workspace
    ) {
        AgentStepType runStepType = runTerminalStepType(command.outcome());
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
                taskStepType(command.outcome()),
                taskPayload,
                command.runEventId(),
                workspace
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

    private LockedExecution lockExecution(String sessionId, String taskId, String runId) {
        requireLockedActiveSession(sessionId);
        AgentTaskEntity task = requireLockedTask(taskId, sessionId);
        AgentRunEntity run = runMapper.selectForUpdate(runId);
        if (run == null) {
            throw failure(Code.UNKNOWN_RUN, "Unknown Run: " + runId);
        }
        if (!belongsTo(run, task, sessionId)) {
            throw failure(Code.STATE_CONFLICT, "Run does not belong to Task and Session");
        }
        AgentWorkspaceEntity workspace = requireLockedReadyWorkspace(sessionId);
        return new LockedExecution(task, run, workspace);
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

    private boolean eligibleTaskAndWorkspace(LockedExecution locked) {
        return AgentTask.Status.ACTIVE.name().equals(locked.task().getStatus())
                && locked.run().getRunId().equals(locked.task().getCurrentRunId())
                && "READY".equals(locked.workspace().getStatus());
    }

    private void requireTerminalOwnership(TerminalCommand command, LockedExecution locked) {
        if (!eligibleTaskAndWorkspace(locked)
                || !AgentRun.Status.RUNNING.name().equals(locked.run().getStatus())
                || !command.workerId().equals(locked.run().getLeaseOwner())
                || !Objects.equals(locked.run().getCurrentFencingToken(), command.fencingToken())
                || !command.runId().equals(locked.workspace().getWriterRunId())
                || !Objects.equals(locked.workspace().getLastAcceptedFencingToken(), command.fencingToken())) {
            throw failure(Code.LEASE_LOST, "Run no longer owns Task execution and Workspace mutation");
        }
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
        payload.set("request", command.request());
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
            String reason,
            Long expiredFencingToken
    ) {
        ObjectNode payload = canonicalJson.objectNode();
        payload.put("reason", reason);
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

    private static String recoveryDispatchEventId(String runId, long expiredFencingToken) {
        return "run:dispatch:" + runId + ":recovery:" + expiredFencingToken;
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

    private static AgentTask.Status taskStatus(TerminalOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AgentTask.Status.COMPLETED;
            case PARTIAL -> AgentTask.Status.WAITING_USER;
            case FAILED -> AgentTask.Status.ACTIVE;
            case CANCELLED -> AgentTask.Status.CANCELLED;
        };
    }

    private static AgentStepType runTerminalStepType(TerminalOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AgentStepType.RUN_COMPLETED;
            case PARTIAL -> AgentStepType.RUN_PARTIAL;
            case FAILED -> AgentStepType.RUN_FAILED;
            case CANCELLED -> AgentStepType.RUN_CANCELLED;
        };
    }

    private static AgentStepType taskStepType(TerminalOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AgentStepType.TASK_COMPLETED;
            case PARTIAL -> AgentStepType.TASK_WAITING_USER;
            case FAILED -> AgentStepType.TASK_RUN_FAILED;
            case CANCELLED -> AgentStepType.TASK_CANCELLED;
        };
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
                task.getRequestJson(),
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
                run.getExecutionConfigJson(),
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

    private static boolean belongsTo(
            AgentRunEntity run,
            AgentTaskEntity task,
            String sessionId
    ) {
        return task.getTaskId().equals(run.getTaskId())
                && sessionId.equals(run.getSessionId());
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

    private record LockedExecution(
            AgentTaskEntity task,
            AgentRunEntity run,
            AgentWorkspaceEntity workspace
    ) {
    }
}
