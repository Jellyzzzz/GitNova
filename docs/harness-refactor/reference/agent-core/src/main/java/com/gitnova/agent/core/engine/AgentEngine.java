package com.gitnova.agent.core.engine;

public interface AgentEngine {
 AgentOutcome run(TaskInput input, SessionRuntime session, ExecutionControl control);
}
