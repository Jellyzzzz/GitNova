package com.gitnova.service.agent.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class AgentRunRecoveryScanner {
    private static final Logger logger = LoggerFactory.getLogger(
            AgentRunRecoveryScanner.class
    );
    private static final int BATCH_SIZE = 100;

    private final AgentTaskRunStore agentTaskRunStore;

    public AgentRunRecoveryScanner(AgentTaskRunStore agentTaskRunStore) {
        this.agentTaskRunStore = Objects.requireNonNull(
                agentTaskRunStore,
                "agentTaskRunStore must not be null"
        );
    }

    @Scheduled(fixedDelay = 5000)
    public void scan() {
        List<AgentRun> expiredRuns = agentTaskRunStore.findExpiredRuns(BATCH_SIZE);
        for (AgentRun run : expiredRuns) {
            try {
                String eventId = "run:lease-expired:"
                        + run.runId()
                        + ":"
                        + run.currentFencingToken();
                agentTaskRunStore.recordLeaseExpired(
                        new AgentTaskRunStore.LeaseExpiryCommand(
                                eventId,
                                run.sessionId(),
                                run.taskId(),
                                run.runId(),
                                run.currentFencingToken()
                        )
                );
            } catch (RuntimeException exception) {
                logger.error(
                        "Could not record expired Agent Run lease: runId={}, fence={}",
                        run.runId(),
                        run.currentFencingToken(),
                        exception
                );
            }
        }
    }
}
