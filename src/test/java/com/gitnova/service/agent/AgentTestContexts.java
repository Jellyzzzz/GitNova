package com.gitnova.service.agent;

import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceId;

/** Shared construction of trusted Agent contexts in tests. */
public final class AgentTestContexts {

    private AgentTestContexts() {
    }

    public static AgentExecutionContext agent(AgentRunContext run) {
        return agent(run, WorkspaceId.generate());
    }

    public static AgentExecutionContext agent(
            AgentRunContext run,
            WorkspaceId workspaceId
    ) {
        return new AgentExecutionContext(
                "session-" + run.runId(),
                run,
                1L,
                "test task",
                new WorkspaceBinding(workspaceId),
                AgentCapabilityPolicy.cloudAgent()
        );
    }

    public static ToolExecutionContext toolExecution(
            AgentRunContext run,
            int turn,
            String toolCallId
    ) {
        return new ToolExecutionContext(agent(run), turn, toolCallId);
    }

    public static ToolExecutionContext toolExecution(
            AgentExecutionContext agent,
            int turn,
            String toolCallId
    ) {
        return new ToolExecutionContext(agent, turn, toolCallId);
    }

    public static ToolExecutionContext workspaceToolExecution(
            AgentRunContext run,
            int turn,
            String toolCallId,
            WorkspaceId workspaceId
    ) {
        return new ToolExecutionContext(agent(run, workspaceId), turn, toolCallId);
    }
}
