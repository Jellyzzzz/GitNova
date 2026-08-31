package com.gitnova.service.agent.execution;

public interface DurableRunExecutor {
    void execute(String runId, String workerId, long fencingToken);
}
