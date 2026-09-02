package com.gitnova.service.agent.execution;

import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentExecutionConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunRecoveryScannerTest {

    @Test
    void shouldContinueScanningAfterOneExpiredRunFails() {
        AgentTaskRunStore store = mock(AgentTaskRunStore.class);
        when(store.findExpiredRuns(100)).thenReturn(List.of(
                expiredRun("run-1", 3L),
                expiredRun("run-2", 4L)
        ));
        when(store.recordLeaseExpired(any())).thenAnswer(invocation -> {
            AgentTaskRunStore.LeaseExpiryCommand command = invocation.getArgument(0);
            if (command.runId().equals("run-1")) {
                throw new IllegalStateException("isolated row failure");
            }
            return new AgentTaskRunStore.LeaseExpiryResult(true, expiredRun("run-2", 4L));
        });

        new AgentRunRecoveryScanner(store).scan();

        var commands = org.mockito.ArgumentCaptor.forClass(
                AgentTaskRunStore.LeaseExpiryCommand.class
        );
        verify(store, times(2)).recordLeaseExpired(commands.capture());
        assertEquals(List.of("run-1", "run-2"), commands.getAllValues()
                .stream()
                .map(AgentTaskRunStore.LeaseExpiryCommand::runId)
                .toList());
    }

    private AgentRun expiredRun(String runId, long fencingToken) {
        Instant now = Instant.parse("2026-08-31T08:00:00Z");
        return new AgentRun(
                runId,
                "session-1",
                "task-1",
                1L,
                null,
                AgentRun.Status.RUNNING,
                1L,
                "worker-a",
                now.minusSeconds(1),
                fencingToken,
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                ),
                "a".repeat(64),
                null,
                1L,
                now.minusSeconds(30),
                now.minusSeconds(20),
                now.minusSeconds(10),
                null,
                now
        );
    }
}
