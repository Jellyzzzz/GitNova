package com.gitnova.service.agent.tool;

import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;

import java.util.Objects;

/**
 * 一次工具调用的可信执行上下文。
 *
 * 与模型传入的 arguments 分离：
 * context、turn、toolCallId 均由 Harness 创建，
 * 模型不能直接修改这些字段。
 *
 * @param agent      本次工具调用所属的可信 Agent 执行上下文
 * @param turn       当前 Agent 循环轮次，从 0 开始
 * @param toolCallId 模型返回的工具调用 ID
 * @param observedWorkspaceGeneration Runtime 在分发边界刷新得到的 Workspace generation；
 *                                    仅用于反馈，不能替代工具锁内的 fencing 校验
 */
public record ToolExecutionContext(
        AgentExecutionContext agent,
        int turn,
        String toolCallId,
        Long observedWorkspaceGeneration
) {
    public ToolExecutionContext(
            AgentExecutionContext agent,
            int turn,
            String toolCallId
    ) {
        this(agent, turn, toolCallId, null);
    }

    public ToolExecutionContext {
        Objects.requireNonNull(agent);
        Objects.requireNonNull(toolCallId);

        if (turn < 0) {
            throw new IllegalArgumentException(
                    "turn must not be negative"
            );
        }
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException(
                    "toolCallId must not be blank"
            );
        }
        if (observedWorkspaceGeneration != null && observedWorkspaceGeneration < 0) {
            throw new IllegalArgumentException(
                    "observedWorkspaceGeneration must not be negative"
            );
        }
    }

    public AgentRunContext run() {
        return agent.context();
    }

    public WorkspaceId requireWorkspaceId() {
        return agent.workspace().workspaceId();
    }

    public WorkspaceExecutionPermit requireExecutionPermit() {
        return Objects.requireNonNull(
                agent.executionPermit(),
                "Workspace execution permit is required"
        );
    }

    public AgentCapabilityPolicy capabilities() {
        return agent.capabilities();
    }
}
