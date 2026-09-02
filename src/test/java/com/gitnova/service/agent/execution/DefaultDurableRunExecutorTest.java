package com.gitnova.service.agent.execution;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.gitnova.service.agent.runtime.AgentRunResult;
import com.gitnova.service.agent.runtime.AgentRunStatus;
import com.gitnova.service.agent.runtime.AgentRuntime;
import com.gitnova.service.agent.runtime.AgentTerminationReason;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.session.AgentSession;
import com.gitnova.service.session.AgentSessionStore;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDurableRunExecutorTest {

    @Test
    void shouldStopWithoutTerminalTransitionWhenHeartbeatConfirmsLeaseLoss() {
        AgentTaskRunStore taskRunStore = mock(AgentTaskRunStore.class);
        AgentSessionStore sessionStore = mock(AgentSessionStore.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeatTask = new AtomicReference<>();

        AgentRun run = runningRun();
        when(taskRunStore.findRun(run.runId())).thenReturn(Optional.of(run));
        when(taskRunStore.findTask(run.taskId())).thenReturn(Optional.of(activeTask()));
        when(sessionStore.findById(run.sessionId())).thenReturn(Optional.of(activeSession()));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(10))))
                .thenAnswer(invocation -> {
                    heartbeatTask.set(invocation.getArgument(0));
                    return heartbeatFuture;
                });
        when(taskRunStore.heartbeat(any())).thenReturn(
                AgentTaskRunStore.HeartbeatResult.LEASE_LOST
        );
        when(runtime.run(any(), any())).thenAnswer(invocation -> {
            heartbeatTask.get().run();
            return failedRuntimeResult();
        });

        new DefaultDurableRunExecutor(
                taskRunStore,
                sessionStore,
                runtime,
                scheduler
        ).execute(run.runId(), "worker-a", 3L);

        verify(taskRunStore, never()).terminateRun(any());
        verify(heartbeatFuture).cancel(false);
    }

    private AgentRun runningRun() {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new AgentRun(
                "run-1",
                "session-1",
                "task-1",
                1L,
                null,
                AgentRun.Status.RUNNING,
                1L,
                "worker-a",
                now.plusSeconds(30),
                3L,
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                ),
                "a".repeat(64),
                null,
                1L,
                now.minusSeconds(10),
                now.minusSeconds(5),
                now.minusSeconds(1),
                null,
                now
        );
    }

    private AgentTask activeTask() {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new AgentTask(
                "task-1",
                "session-1",
                "create-1",
                7L,
                AgentTask.Status.ACTIVE,
                new AgentTaskRequest("review"),
                "b".repeat(64),
                "run-1",
                1L,
                null,
                1L,
                now,
                now,
                null
        );
    }

    private AgentSession activeSession() {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new AgentSession(
                "session-1",
                "create-session-1",
                7L,
                RepoKey.of(7L, 42L),
                WorkspaceId.parse("10000000-0000-0000-0000-000000000001"),
                new SnapshotScope(GitObjectId.of("c".repeat(40))),
                AgentSession.Status.ACTIVE,
                1L,
                1L,
                now,
                now
        );
    }

    private AgentRunResult failedRuntimeResult() {
        return new AgentRunResult(
                AgentRunStatus.FAILED,
                AgentTerminationReason.MODEL_GATEWAY_FAILURE,
                null,
                null,
                1,
                0,
                0,
                List.of()
        );
    }
}
