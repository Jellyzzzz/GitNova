package com.gitnova.service.agent.dispatch;

import com.gitnova.service.agent.execution.AgentRun;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunDispatchWorkerTest {
    private static final String SESSION_ID = "session-1";
    private static final String TASK_ID = "task-1";
    private static final String RUN_ID = "run-1";
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-30T08:00:00Z");

    @Mock
    AgentTaskRunStore taskRunStore;

    @Mock
    DurableRunExecutor runExecutor;

    @Mock
    Channel channel;

    @Test
    void shouldAckAnUnknownRunWithoutExecuting() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.empty());

        worker().consume(initialDispatch(), channel, 41L);

        verify(channel).basicAck(41L, false);
        verify(taskRunStore, never()).claimRun(any());
        verify(runExecutor, never()).execute(any(), any(), any(Long.class));
    }

    @Test
    void shouldAckANonQueuedRunWithoutExecuting() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.of(runningRun("worker-a", 3L)));

        worker().consume(initialDispatch(), channel, 42L);

        verify(channel).basicAck(42L, false);
        verify(taskRunStore, never()).claimRun(any());
        verify(runExecutor, never()).execute(any(), any(), any(Long.class));
    }

    @Test
    void shouldClaimAckAndExecuteAQueuedRunInOrder() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.of(queuedRun()));
        when(taskRunStore.claimRun(any())).thenAnswer(invocation -> {
            AgentTaskRunStore.ClaimCommand command = invocation.getArgument(0);
            return new AgentTaskRunStore.ClaimResult(
                    AgentTaskRunStore.ClaimDisposition.CLAIMED,
                    runningRun(command.workerId(), 3L)
            );
        });

        worker().consume(initialDispatch(), channel, 43L);

        ArgumentCaptor<AgentTaskRunStore.ClaimCommand> claim =
                ArgumentCaptor.forClass(AgentTaskRunStore.ClaimCommand.class);
        InOrder order = inOrder(taskRunStore, channel, runExecutor);
        order.verify(taskRunStore).findRun(RUN_ID);
        order.verify(taskRunStore).claimRun(claim.capture());
        order.verify(channel).basicAck(43L, false);
        order.verify(runExecutor).execute(RUN_ID, claim.getValue().workerId(), 3L);
        assertEquals(30, claim.getValue().leaseSeconds());
        assertTrue(claim.getValue().eventId().startsWith("run:claimed:run-1:"));
    }

    @Test
    void shouldAckWithoutExecutingWhenTheClaimLosesItsCas() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.of(queuedRun()));
        when(taskRunStore.claimRun(any())).thenReturn(new AgentTaskRunStore.ClaimResult(
                AgentTaskRunStore.ClaimDisposition.NOT_CLAIMABLE,
                null
        ));

        worker().consume(initialDispatch(), channel, 44L);

        verify(channel).basicAck(44L, false);
        verify(runExecutor, never()).execute(any(), any(), any(Long.class));
    }

    @Test
    void shouldTakeOverAckAndExecuteARecoveryDispatchInOrder() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.of(runningRun("worker-a", 3L)));
        when(taskRunStore.takeoverRun(any())).thenAnswer(invocation -> {
            AgentTaskRunStore.TakeoverCommand command = invocation.getArgument(0);
            return new AgentTaskRunStore.TakeoverResult(
                    AgentTaskRunStore.TakeoverDisposition.TAKEN_OVER,
                    runningRun(command.workerId(), 4L)
            );
        });

        worker().consume(
                new RunDispatchMessage(
                        "dispatch-recovery",
                        RUN_ID,
                        RunDispatchReason.RECOVERY,
                        3L
                ),
                channel,
                45L
        );

        ArgumentCaptor<AgentTaskRunStore.TakeoverCommand> takeover =
                ArgumentCaptor.forClass(AgentTaskRunStore.TakeoverCommand.class);
        InOrder order = inOrder(taskRunStore, channel, runExecutor);
        order.verify(taskRunStore).findRun(RUN_ID);
        order.verify(taskRunStore).takeoverRun(takeover.capture());
        order.verify(channel).basicAck(45L, false);
        order.verify(runExecutor).execute(RUN_ID, takeover.getValue().workerId(), 4L);
        assertEquals(3L, takeover.getValue().expiredFencingToken());
        assertEquals(30, takeover.getValue().leaseSeconds());
    }

    @Test
    void shouldAckAStaleRecoveryDispatchWithoutBatchAck() throws Exception {
        when(taskRunStore.findRun(RUN_ID)).thenReturn(Optional.of(queuedRun()));

        worker().consume(
                new RunDispatchMessage(
                        "dispatch-recovery",
                        RUN_ID,
                        RunDispatchReason.RECOVERY,
                        3L
                ),
                channel,
                46L
        );

        verify(channel).basicAck(46L, false);
        verify(taskRunStore, never()).takeoverRun(any());
        verify(runExecutor, never()).execute(any(), any(), any(Long.class));
    }

    private RunDispatchWorker worker() {
        return new RunDispatchWorker(taskRunStore, runExecutor);
    }

    private RunDispatchMessage initialDispatch() {
        return new RunDispatchMessage(
                "dispatch-1",
                RUN_ID,
                RunDispatchReason.INITIAL,
                null
        );
    }

    private AgentExecutionConfig executionConfig() {
        return com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                Set.of(AgentCapability.CODE_READ)
        );
    }

    private AgentRun queuedRun() {
        return new AgentRun(
                RUN_ID,
                SESSION_ID,
                TASK_ID,
                1L,
                null,
                AgentRun.Status.QUEUED,
                1L,
                null,
                null,
                null,
                executionConfig(),
                DIGEST,
                null,
                1L,
                NOW,
                null,
                null,
                null,
                NOW
        );
    }

    private AgentRun runningRun(String workerId, long fencingToken) {
        return new AgentRun(
                RUN_ID,
                SESSION_ID,
                TASK_ID,
                1L,
                null,
                AgentRun.Status.RUNNING,
                2L,
                workerId,
                NOW.plusSeconds(30),
                fencingToken,
                executionConfig(),
                DIGEST,
                null,
                2L,
                NOW.minusSeconds(10),
                NOW.minusSeconds(5),
                NOW.minusSeconds(1),
                null,
                NOW
        );
    }
}
