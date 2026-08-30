package com.gitnova.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;

public interface DurableRunExecutor {
    void execute(String runId, String workerId, long fencingToken) throws JsonProcessingException;
}
