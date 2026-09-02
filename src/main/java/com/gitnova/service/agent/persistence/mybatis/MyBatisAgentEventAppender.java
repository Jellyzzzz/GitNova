package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.entity.agent.AgentTaskEntity;
import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.mapper.agent.AgentTaskMapper;
import com.gitnova.mapper.agent.AgentRunMapper;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** MySQL implementation of the shared append-only event boundary. */
@Repository
public class MyBatisAgentEventAppender implements AgentEventAppender {

    private final AgentSessionMapper sessionMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final CanonicalJsonCodec canonicalJson;

    public MyBatisAgentEventAppender(
            AgentSessionMapper sessionMapper,
            AgentTaskMapper taskMapper,
            AgentRunMapper runMapper,
            AgentStepMapper stepMapper,
            CanonicalJsonCodec canonicalJson
    ) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper must not be null");
        this.stepMapper = Objects.requireNonNull(stepMapper, "stepMapper must not be null");
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult append(AppendCommand command) {
        return appendInternal(command,null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult appendFence(AppendCommand command,RunExecutionAuthority authority){
        Objects.requireNonNull(command,"command must not be null");
        Objects.requireNonNull(authority,"authority must not be null");
        if(command.runId()==null) throw new IllegalArgumentException("runId must not be null");
        return appendInternal(command,authority);
    }

    private AgentTaskEntity lockAndVerifyTask(AppendCommand command) {
        if (command.taskId() == null) {
            return null;
        }
        AgentTaskEntity task = taskMapper.selectForUpdate(command.taskId());
        if (task == null) {
            throw failure(
                    AgentExecutionPersistenceException.Code.UNKNOWN_TASK,
                    "Unknown Task: " + command.taskId()
            );
        }
        if (!command.sessionId().equals(task.getSessionId())) {
            throw failure(
                    AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Task does not belong to Session"
            );
        }
        return task;
    }

    private AgentRunEntity lockAndVerifyRun(
            AppendCommand command,
            AgentTaskEntity task
    ) {
        if (command.runId() == null) {
            return null;
        }
        AgentRunEntity run = runMapper.selectForUpdate(command.runId());
        if (run == null) {
            throw failure(
                    AgentExecutionPersistenceException.Code.UNKNOWN_RUN,
                    "Unknown Run: " + command.runId()
            );
        }
        if (task == null
                || !command.sessionId().equals(run.getSessionId())
                || !task.getTaskId().equals(run.getTaskId())) {
            throw failure(
                    AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Run does not belong to Task and Session"
            );
        }
        return run;
    }

    private String eventDigest(AppendCommand command, String payloadDigest) {
        ObjectNode identity = canonicalJson.objectNode();
        identity.put("sessionId", command.sessionId());
        putNullable(identity, "taskId", command.taskId());
        putNullable(identity, "runId", command.runId());
        identity.put("stepType", command.stepType().name());
        identity.put("schemaVersion", command.schemaVersion());
        identity.put("payloadDigest", payloadDigest);
        putNullable(identity, "causationEventId", command.causationEventId());
        putNullable(identity, "correlationId", command.correlationId());
        putNullable(identity, "workspaceEpoch", command.workspaceEpoch());
        putNullable(identity, "workspaceGeneration", command.workspaceGeneration());
        return canonicalJson.encode(identity).digest();
    }

    private AppendResult appendInternal(AppendCommand command,RunExecutionAuthority authority){
        Objects.requireNonNull(command, "command must not be null");
        CanonicalJsonCodec.EncodedJson payload = canonicalJson.encode(command.persistedPayload());
        String payloadJson = payload.json();
        String payloadDigest = payload.digest();
        String eventDigest = eventDigest(command, payloadDigest);

        AgentSessionEntity session=sessionMapper.selectForUpdate(command.sessionId());
        if(session==null) throw failure(
                AgentExecutionPersistenceException.Code.UNKNOWN_SESSION,
                "Unknown Session: " + command.sessionId()
        );

        AgentTaskEntity task=lockAndVerifyTask(command);
        AgentRunEntity run=lockAndVerifyRun(command,task);
        if(authority!=null) verifyExecutionAuthority(run,authority);

        AgentStepEntity existing=stepMapper.selectByEventId(command.eventId());
        if(existing!=null){
            if (!eventDigest.equals(existing.getEventDigest())){
                throw failure(
                        AgentExecutionPersistenceException.Code.IDEMPOTENCY_KEY_CONFLICT,
                        "eventId is already committed with different event semantics: " + command.eventId()
                );
            }
            return new AppendResult(existing.getStepId(),existing.getSessionSequence(),existing.getRunStepSequence(),true);
        }
        long previousSessionSequence = requireNonNegative(
                session.getLastSessionSequence(),
                "lastSessionSequence"
        );
        long nextSessionSequence=Math.incrementExact(previousSessionSequence);
        if(sessionMapper.advanceSequence(session.getSessionId(),previousSessionSequence,nextSessionSequence)!=1){
            throw failure(
                    AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                    "Could not advance Session sequence"
            );
        }
        Long nextRunStepSequence = null;

        if (run != null) {
            long previousRunStepSequence = requireNonNegative(
                    run.getLastRunStepSequence(),
                    "lastRunStepSequence"
            );
            long next = Math.incrementExact(previousRunStepSequence);
            if (runMapper.advanceStepSequence(
                    run.getRunId(),
                    previousRunStepSequence,
                    next
            ) != 1) {
                throw failure(
                        AgentExecutionPersistenceException.Code.STATE_CONFLICT,
                        "Could not advance Run sequence"
                );
            }
            nextRunStepSequence = next;
        }
        AgentStepEntity step = new AgentStepEntity();
        step.setEventId(command.eventId());
        step.setEventDigest(eventDigest);
        step.setSessionId(command.sessionId());
        step.setSessionSequence(nextSessionSequence);
        step.setTaskId(command.taskId());
        step.setRunId(command.runId());
        step.setRunStepSequence(nextRunStepSequence);
        step.setStepType(command.stepType().name());
        step.setSchemaVersion(command.schemaVersion());
        step.setPayloadJson(payloadJson);
        step.setPersistedPayloadDigest(payloadDigest);
        step.setCausationEventId(command.causationEventId());
        step.setCorrelationId(command.correlationId());
        step.setWorkspaceEpoch(command.workspaceEpoch());
        step.setWorkspaceGeneration(command.workspaceGeneration());
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if(stepMapper.insert(step)!=1||step.getStepId()==null){
            throw failure(
                    AgentExecutionPersistenceException.Code.PERSISTENCE_FAILURE,
                    "Could not append Agent Step"
            );
        }
        return new AppendResult(step.getStepId(),step.getSessionSequence(),step.getRunStepSequence(),false);
    }

    private void verifyExecutionAuthority(AgentRunEntity run,RunExecutionAuthority authority){
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        if(runMapper.hasValidLease(run.getRunId(), authority.workerId(),authority.fencingToken())!=1){
            throw failure(AgentExecutionPersistenceException.Code.LEASE_LOST,"Run execution authority has been lost");
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static long requireNonNegative(Long value, String field) {
        if (value == null || value < 0) {
            throw new IllegalStateException(field + " must be a persisted non-negative value");
        }
        return value;
    }

    private static AgentExecutionPersistenceException failure(
            AgentExecutionPersistenceException.Code code,
            String message
    ) {
        return new AgentExecutionPersistenceException(code, message);
    }
}
