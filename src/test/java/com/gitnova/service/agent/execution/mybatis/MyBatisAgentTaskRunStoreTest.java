package com.gitnova.service.agent.execution.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.entity.agent.AgentRunEntity;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentTaskEntity;
import com.gitnova.entity.agent.AgentWorkspaceEntity;
import com.gitnova.mapper.agent.AgentRunMapper;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentTaskMapper;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTask;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.CreateTaskCommand;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.AgentOutboxWriter;
import com.gitnova.service.agent.persistence.AgentStepType;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisAgentTaskRunStoreTest {
    private static final String SESSION_ID = "session-1";
    private static final String TASK_ID = "task-1";
    private static final String RUN_ID = "run-1";
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String DIGEST = "a".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 29, 12, 0);

    @Mock
    AgentSessionMapper sessionMapper;
    @Mock
    AgentTaskMapper taskMapper;
    @Mock
    AgentRunMapper runMapper;
    @Mock
    AgentWorkspaceMapper workspaceMapper;
    @Mock
    AgentEventAppender eventAppender;
    @Mock
    AgentOutboxWriter outboxWriter;

    @Test
    void createShouldPersistTaskInitialRunTimelineAndDispatchIntent() {
        String sessionId = "10000000-0000-0000-0000-000000000001";
        String taskId = "10000000-0000-0000-0000-000000000002";
        String runId = "10000000-0000-0000-0000-000000000003";
        String workspaceId = "10000000-0000-0000-0000-000000000004";
        AgentSessionEntity session = activeSession(sessionId);
        AgentWorkspaceEntity workspace = workspace(workspaceId, sessionId, null, 0L);
        AtomicReference<AgentTaskEntity> taskRow = new AtomicReference<>();
        AtomicReference<AgentRunEntity> runRow = new AtomicReference<>();
        when(sessionMapper.selectForUpdate(sessionId)).thenReturn(session);
        when(taskMapper.claimCreationIdentity(any())).thenAnswer(invocation -> {
            taskRow.set(invocation.getArgument(0));
            return 1;
        });
        when(taskMapper.selectForUpdateByCreationIdentity(sessionId, "create-1"))
                .thenAnswer(invocation -> taskRow.get());
        when(workspaceMapper.selectBySessionId(sessionId)).thenReturn(workspace);
        when(workspaceMapper.selectForUpdate(workspaceId)).thenReturn(workspace);
        when(runMapper.insert(any())).thenAnswer(invocation -> {
            runRow.set(invocation.getArgument(0));
            return 1;
        });
        when(taskMapper.attachRun(taskId, sessionId, runId, 0L, 1L)).thenAnswer(invocation -> {
            taskRow.get().setCurrentRunId(runId);
            taskRow.get().setLastRunNumber(1L);
            taskRow.get().setVersion(1L);
            return 1;
        });
        when(taskMapper.selectById(taskId)).thenAnswer(invocation -> taskRow.get());
        when(runMapper.selectById(runId)).thenAnswer(invocation -> runRow.get());
        ObjectMapper json = new ObjectMapper();

        AgentTaskRunStore.CreateResult result = store().createTaskWithInitialRun(
                new CreateTaskCommand(
                        "create-1",
                        taskId,
                        runId,
                        sessionId,
                        9L,
                        json.createObjectNode().put("goal", "review"),
                        json.createObjectNode().put("model", "test")
                )
        );

        assertTrue(result.created());
        assertEquals(AgentTask.Status.ACTIVE, result.task().status());
        assertEquals(runId, result.task().currentRunId());
        assertEquals(AgentRun.Status.QUEUED, result.initialRun().status());
        ArgumentCaptor<AgentEventAppender.AppendCommand> events =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender, org.mockito.Mockito.times(3)).append(events.capture());
        assertEquals(AgentStepType.USER_MESSAGE_RECEIVED, events.getAllValues().get(0).stepType());
        assertEquals(AgentStepType.TASK_CREATED, events.getAllValues().get(1).stepType());
        assertEquals(AgentStepType.RUN_QUEUED, events.getAllValues().get(2).stepType());
        ArgumentCaptor<AgentOutboxWriter.EnqueueCommand> outbox =
                ArgumentCaptor.forClass(AgentOutboxWriter.EnqueueCommand.class);
        verify(outboxWriter).enqueue(outbox.capture());
        assertEquals("run:dispatch:" + runId + ":initial", outbox.getValue().eventId());
    }

    @Test
    void heartbeatShouldOnlyExtendTheLeaseCas() {
        when(runMapper.heartbeat(RUN_ID, "worker-a", 7L, 30)).thenReturn(1);

        AgentTaskRunStore.HeartbeatResult result = store().heartbeat(
                new AgentTaskRunStore.HeartbeatCommand(RUN_ID, "worker-a", 7L, 30)
        );

        assertEquals(AgentTaskRunStore.HeartbeatResult.EXTENDED, result);
        verify(eventAppender, never()).append(any());
        verify(outboxWriter, never()).enqueue(any());
    }

    @Test
    void heartbeatShouldReportLeaseLostWhenOwnerOrFenceNoLongerMatches() {
        when(runMapper.heartbeat(RUN_ID, "stale-worker", 6L, 30)).thenReturn(0);

        AgentTaskRunStore.HeartbeatResult result = store().heartbeat(
                new AgentTaskRunStore.HeartbeatCommand(RUN_ID, "stale-worker", 6L, 30)
        );

        assertEquals(AgentTaskRunStore.HeartbeatResult.LEASE_LOST, result);
    }

    @Test
    void duplicateClaimShouldUseTheDatabaseLeaseClock() {
        AgentRunEntity running = runningRun("worker-a", 7L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 7L);
        stubLockedExecution(running, workspace);
        when(runMapper.hasValidLease(RUN_ID, "worker-a", 7L)).thenReturn(1);

        AgentTaskRunStore.ClaimResult result = store().claimRun(
                new AgentTaskRunStore.ClaimCommand(
                        "run:claimed:run-1:7",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-a",
                        30
                )
        );

        assertEquals(AgentTaskRunStore.ClaimDisposition.ALREADY_CLAIMED, result.disposition());
        assertEquals(7L, result.run().currentFencingToken());
        verify(workspaceMapper, never()).claimWriter(any(), any(), any(Long.class), any(Long.class));
        verify(eventAppender, never()).append(any());
    }

    @Test
    void expiredLeaseShouldAppendEvidenceAndEnqueueRecoveryInOneStoreTransaction() {
        AgentRunEntity expired = runningRun("worker-a", 5L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 5L);
        when(sessionMapper.selectForUpdate(SESSION_ID)).thenReturn(activeSession());
        when(taskMapper.selectForUpdate(TASK_ID)).thenReturn(activeTask());
        when(runMapper.selectExpiredForUpdate(RUN_ID, 5L)).thenReturn(expired);
        when(workspaceMapper.selectBySessionId(SESSION_ID)).thenReturn(workspace);
        when(workspaceMapper.selectForUpdate(WORKSPACE_ID)).thenReturn(workspace);
        when(runMapper.selectById(RUN_ID)).thenReturn(expired);

        AgentTaskRunStore.LeaseExpiryResult result = store().recordLeaseExpired(
                new AgentTaskRunStore.LeaseExpiryCommand(
                        "run:lease-expired:run-1:5",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        5L
                )
        );

        assertTrue(result.recorded());
        ArgumentCaptor<AgentEventAppender.AppendCommand> step =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender).append(step.capture());
        assertEquals(AgentStepType.RUN_LEASE_EXPIRED, step.getValue().stepType());
        assertEquals(5L, step.getValue().persistedPayload().get("expiredFencingToken").longValue());
        ArgumentCaptor<AgentOutboxWriter.EnqueueCommand> outbox =
                ArgumentCaptor.forClass(AgentOutboxWriter.EnqueueCommand.class);
        verify(outboxWriter).enqueue(outbox.capture());
        assertEquals("run:dispatch:run-1:recovery:5", outbox.getValue().eventId());
        assertEquals("RUN_DISPATCH_REQUESTED", outbox.getValue().eventType());
        assertEquals("RECOVERY", outbox.getValue().payload().get("reason").textValue());
    }

    @Test
    void takeoverShouldKeepTheRunAndAdvanceBothOwnershipFences() {
        AgentRunEntity expired = runningRun("worker-a", 5L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 5L);
        stubLockedExecution(expired, workspace);
        when(workspaceMapper.takeoverWriter(WORKSPACE_ID, RUN_ID, 5L, 6L)).thenReturn(1);
        when(runMapper.takeover(RUN_ID, "worker-b", 5L, 6L, 30)).thenReturn(1);
        when(runMapper.selectById(RUN_ID)).thenReturn(runningRun("worker-b", 6L));

        AgentTaskRunStore.TakeoverResult result = store().takeoverRun(
                new AgentTaskRunStore.TakeoverCommand(
                        "run:taken-over:run-1:6",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-b",
                        5L,
                        30
                )
        );

        assertEquals(AgentTaskRunStore.TakeoverDisposition.TAKEN_OVER, result.disposition());
        assertEquals(RUN_ID, result.run().runId());
        assertEquals(6L, result.run().currentFencingToken());
        verify(workspaceMapper).takeoverWriter(WORKSPACE_ID, RUN_ID, 5L, 6L);
        ArgumentCaptor<AgentEventAppender.AppendCommand> step =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender).append(step.capture());
        assertEquals(AgentStepType.RUN_TAKEN_OVER, step.getValue().stepType());
        assertEquals(6L, step.getValue().persistedPayload().get("fencingToken").longValue());
    }

    @Test
    void failedRunShouldReleaseWriterButKeepTaskActiveForAnotherRun() {
        AgentRunEntity running = runningRun("worker-a", 9L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 9L);
        stubLockedExecution(running, workspace);
        when(runMapper.terminate(RUN_ID, "worker-a", 9L, "FAILED", "MODEL_ERROR"))
                .thenReturn(1);
        when(taskMapper.transitionAfterRun(
                TASK_ID, SESSION_ID, RUN_ID, "ACTIVE", null, null
        )).thenReturn(1);
        when(workspaceMapper.releaseWriter(WORKSPACE_ID, RUN_ID, 9L, 10L)).thenReturn(1);
        when(taskMapper.selectById(TASK_ID)).thenReturn(activeTaskWithoutRun());
        when(runMapper.selectById(RUN_ID)).thenReturn(terminalRun());

        AgentTaskRunStore.TerminalResult result = store().terminateRun(
                new AgentTaskRunStore.TerminalCommand(
                        "run:failed:run-1",
                        "task:run-failed:task-1:run-1",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-a",
                        9L,
                        AgentTaskRunStore.TerminalOutcome.FAILED,
                        "MODEL_ERROR"
                )
        );

        assertEquals(AgentRun.Status.FAILED, result.run().status());
        assertEquals(AgentTask.Status.ACTIVE, result.task().status());
        assertNull(result.task().currentRunId());
        verify(workspaceMapper).releaseWriter(WORKSPACE_ID, RUN_ID, 9L, 10L);

        ArgumentCaptor<AgentEventAppender.AppendCommand> events =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals(AgentStepType.RUN_FAILED, events.getAllValues().get(0).stepType());
        assertEquals(AgentStepType.TASK_RUN_FAILED, events.getAllValues().get(1).stepType());
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLETED, COMPLETED, TASK_COMPLETED",
            "PARTIAL, WAITING_USER, TASK_WAITING_USER",
            "CANCELLED, CANCELLED, TASK_CANCELLED"
    })
    void terminalOutcomesShouldProjectRunAndTaskStates(
            AgentTaskRunStore.TerminalOutcome outcome,
            AgentTask.Status expectedTaskStatus,
            AgentStepType expectedTaskStep
    ) {
        AgentRunEntity running = runningRun("worker-a", 9L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 9L);
        stubLockedExecution(running, workspace);
        when(runMapper.terminate(RUN_ID, "worker-a", 9L, outcome.name(), "TEST_REASON"))
                .thenReturn(1);
        when(taskMapper.transitionAfterRun(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(workspaceMapper.releaseWriter(WORKSPACE_ID, RUN_ID, 9L, 10L)).thenReturn(1);
        when(taskMapper.selectById(TASK_ID)).thenReturn(projectedTask(expectedTaskStatus));
        when(runMapper.selectById(RUN_ID)).thenReturn(terminalRun(outcome.name(), "TEST_REASON"));

        AgentTaskRunStore.TerminalResult result = store().terminateRun(
                new AgentTaskRunStore.TerminalCommand(
                        "run:terminal:run-1:" + outcome.name(),
                        "task:terminal:task-1:" + outcome.name(),
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-a",
                        9L,
                        outcome,
                        "TEST_REASON"
                )
        );

        assertEquals(AgentRun.Status.valueOf(outcome.name()), result.run().status());
        assertEquals(expectedTaskStatus, result.task().status());
        ArgumentCaptor<AgentEventAppender.AppendCommand> events =
                ArgumentCaptor.forClass(AgentEventAppender.AppendCommand.class);
        verify(eventAppender, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals(expectedTaskStep, events.getAllValues().get(1).stepType());
    }

    @Test
    void terminalRetryShouldVerifyTheExistingStepSemanticsWithoutMutatingStateAgain() {
        AgentTaskEntity task = activeTaskWithoutRun();
        AgentRunEntity run = terminalRun();
        AgentWorkspaceEntity workspace = workspace(null, 10L);
        when(sessionMapper.selectForUpdate(SESSION_ID)).thenReturn(activeSession());
        when(taskMapper.selectForUpdate(TASK_ID)).thenReturn(task);
        when(runMapper.selectForUpdate(RUN_ID)).thenReturn(run);
        when(workspaceMapper.selectBySessionId(SESSION_ID)).thenReturn(workspace);
        when(workspaceMapper.selectForUpdate(WORKSPACE_ID)).thenReturn(workspace);

        AgentTaskRunStore.TerminalResult result = store().terminateRun(
                new AgentTaskRunStore.TerminalCommand(
                        "run:failed:run-1",
                        "task:run-failed:task-1:run-1",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-a",
                        9L,
                        AgentTaskRunStore.TerminalOutcome.FAILED,
                        "MODEL_ERROR"
                )
        );

        assertEquals(AgentRun.Status.FAILED, result.run().status());
        assertEquals(AgentTask.Status.ACTIVE, result.task().status());
        verify(runMapper, never()).terminate(any(), any(), any(Long.class), any(), any());
        verify(taskMapper, never()).transitionAfterRun(any(), any(), any(), any(), any(), any());
        verify(workspaceMapper, never()).releaseWriter(any(), any(), any(Long.class), any(Long.class));
        verify(eventAppender, org.mockito.Mockito.times(2)).append(any());
    }

    @Test
    void takeoverShouldRejectAWorkerWhenTheExpectedFenceIsStale() {
        AgentRunEntity run = runningRun("worker-b", 6L);
        AgentWorkspaceEntity workspace = workspace(RUN_ID, 6L);
        stubLockedExecution(run, workspace);

        AgentTaskRunStore.TakeoverResult result = store().takeoverRun(
                new AgentTaskRunStore.TakeoverCommand(
                        "run:taken-over:run-1:6",
                        SESSION_ID,
                        TASK_ID,
                        RUN_ID,
                        "worker-c",
                        5L,
                        30
                )
        );

        assertEquals(AgentTaskRunStore.TakeoverDisposition.NOT_ELIGIBLE, result.disposition());
        assertNull(result.run());
        verify(runMapper, never()).takeover(any(), any(), any(Long.class), any(Long.class), any(Integer.class));
    }

    private MyBatisAgentTaskRunStore store() {
        return new MyBatisAgentTaskRunStore(
                sessionMapper,
                taskMapper,
                runMapper,
                workspaceMapper,
                eventAppender,
                outboxWriter,
                new CanonicalJsonCodec(new ObjectMapper())
        );
    }

    private void stubLockedExecution(AgentRunEntity run, AgentWorkspaceEntity workspace) {
        when(sessionMapper.selectForUpdate(SESSION_ID)).thenReturn(activeSession());
        when(taskMapper.selectForUpdate(TASK_ID)).thenReturn(activeTask());
        when(runMapper.selectForUpdate(RUN_ID)).thenReturn(run);
        when(workspaceMapper.selectBySessionId(SESSION_ID)).thenReturn(workspace);
        when(workspaceMapper.selectForUpdate(WORKSPACE_ID)).thenReturn(workspace);
    }

    private AgentSessionEntity activeSession() {
        return activeSession(SESSION_ID);
    }

    private AgentSessionEntity activeSession(String sessionId) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(sessionId);
        session.setStatus("ACTIVE");
        return session;
    }

    private AgentTaskEntity activeTask() {
        AgentTaskEntity task = baseTask();
        task.setStatus("ACTIVE");
        task.setCurrentRunId(RUN_ID);
        return task;
    }

    private AgentTaskEntity activeTaskWithoutRun() {
        AgentTaskEntity task = baseTask();
        task.setStatus("ACTIVE");
        task.setCurrentRunId(null);
        task.setVersion(2L);
        return task;
    }

    private AgentTaskEntity projectedTask(AgentTask.Status status) {
        AgentTaskEntity task = activeTaskWithoutRun();
        task.setStatus(status.name());
        if (status.terminal()) {
            task.setTerminalReason("TEST_REASON");
            task.setTerminalAt(NOW);
        }
        return task;
    }

    private AgentTaskEntity baseTask() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setTaskId(TASK_ID);
        task.setSessionId(SESSION_ID);
        task.setCreationIdempotencyKey("create-task-1");
        task.setCreatedByActorId(1L);
        task.setRequestJson("{}");
        task.setRequestDigest(DIGEST);
        task.setLastRunNumber(1L);
        task.setVersion(1L);
        task.setCreatedAt(NOW.minusMinutes(5));
        task.setUpdatedAt(NOW);
        return task;
    }

    private AgentWorkspaceEntity workspace(String writerRunId, long fence) {
        return workspace(WORKSPACE_ID, SESSION_ID, writerRunId, fence);
    }

    private AgentWorkspaceEntity workspace(
            String workspaceId,
            String sessionId,
            String writerRunId,
            long fence
    ) {
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setWorkspaceId(workspaceId);
        workspace.setSessionId(sessionId);
        workspace.setStatus("READY");
        workspace.setWriterRunId(writerRunId);
        workspace.setLastAcceptedFencingToken(fence);
        workspace.setWorkspaceEpoch(2L);
        workspace.setGeneration(3L);
        return workspace;
    }

    private AgentRunEntity runningRun(String workerId, long fence) {
        AgentRunEntity run = baseRun();
        run.setStatus("RUNNING");
        run.setLeaseOwner(workerId);
        run.setLeaseUntil(NOW.plusMinutes(1));
        run.setCurrentFencingToken(fence);
        run.setClaimedAt(NOW.minusMinutes(1));
        run.setLastHeartbeatAt(NOW.minusSeconds(10));
        return run;
    }

    private AgentRunEntity terminalRun() {
        return terminalRun("FAILED", "MODEL_ERROR");
    }

    private AgentRunEntity terminalRun(String status, String reason) {
        AgentRunEntity run = baseRun();
        run.setStatus(status);
        run.setCurrentFencingToken(9L);
        run.setTerminationReason(reason);
        run.setFinishedAt(NOW);
        run.setVersion(3L);
        return run;
    }

    private AgentRunEntity baseRun() {
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setTaskId(TASK_ID);
        run.setRunNumber(1L);
        run.setLastRunStepSequence(4L);
        run.setExecutionConfigJson("{}");
        run.setExecutionConfigDigest(DIGEST);
        run.setVersion(2L);
        run.setCreatedAt(NOW.minusMinutes(4));
        run.setUpdatedAt(NOW);
        return run;
    }
}
