package com.gitnova.service.agent.tool;

import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceId;

import java.util.Objects;

/**
 * 一次工具调用的可信执行上下文。
 *
 * 与模型传入的 arguments 分离：
 * run、turn、toolCallId 均由 Harness 创建，
 * 模型不能直接修改这些字段。
 *
 * @param run        本次工具调用所属的 Agent Run
 * @param turn       当前 Agent 循环轮次，从 0 开始
 * @param toolCallId 模型返回的工具调用 ID
 * @param workspace  Harness 绑定的逻辑 Workspace；Review-only 调用为空
 */
public record ToolExecutionContext(
        AgentRunContext run,
        int turn,
        String toolCallId,
        WorkspaceBinding workspace
) {
    public ToolExecutionContext(
            AgentRunContext run,
            int turn,
            String toolCallId
    ) {
        this(run, turn, toolCallId, null);
    }

    public ToolExecutionContext{
        Objects.requireNonNull(run,"run must not be null");
        Objects.requireNonNull(toolCallId,"toolCallId must not be null");

        if(turn<0){
            throw new IllegalArgumentException("turn must not be negative");
        }
        if(toolCallId.isBlank()){
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
    }

    /**
     * Creates a trusted execution context for a Workspace-scoped tool call.
     */
    public static ToolExecutionContext forWorkspace(
            AgentRunContext run,
            int turn,
            String toolCallId,
            WorkspaceId workspaceId
    ) {
        return new ToolExecutionContext(
                run,
                turn,
                toolCallId,
                new WorkspaceBinding(workspaceId)
        );
    }

    public boolean hasWorkspace() {
        return workspace != null;
    }

    /**
     * Returns the server-bound Workspace identity without exposing its host path.
     */
    public WorkspaceId requireWorkspaceId() {
        if (workspace == null) {
            throw new IllegalStateException(
                    "tool execution is not bound to a Workspace"
            );
        }
        return workspace.workspaceId();
    }
}
