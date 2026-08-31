package com.gitnova.service.agent.execution;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRunRecoveryScanner {
    private final AgentTaskRunStore agentTaskRunStore;
    private static final int BASIC_SIZE=100;
    public AgentRunRecoveryScanner(AgentTaskRunStore agentTaskRunStore){
        this.agentTaskRunStore=agentTaskRunStore;
    }
    @Scheduled(fixedDelay = 5000)
    public void scan(){
        List<AgentRun>expiredRuns=agentTaskRunStore.findExpiredRuns(BASIC_SIZE);
        for(AgentRun run:expiredRuns){
            recordExpired(run);
        }
    }

    private void recordExpired(AgentRun run){
        String eventId="run:lease-expired:"
                + run.runId()
                + ":"
                + run.currentFencingToken();
        AgentTaskRunStore.LeaseExpiryCommand command=new AgentTaskRunStore.LeaseExpiryCommand(eventId,run.sessionId(),run.taskId(),run.runId(), run.currentFencingToken());
        agentTaskRunStore.recordLeaseExpired(command);
    }
}
