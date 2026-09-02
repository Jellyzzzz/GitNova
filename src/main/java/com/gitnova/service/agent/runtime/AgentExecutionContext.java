package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;

import java.util.Objects;

public record AgentExecutionContext(
        String sessionId,
        AgentRunContext context,
        long actorId,
        String taskText,
        WorkspaceBinding workspace,
        WorkspaceExecutionPermit executionPermit,
        AgentExecutionConfig executionConfig
) {
    public AgentExecutionContext {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(context);
        Objects.requireNonNull(taskText);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(executionPermit);
        Objects.requireNonNull(executionConfig);

        if (!workspace.workspaceId().equals(executionPermit.workspaceId())
                || !context.runId().equals(executionPermit.runId())) {
            throw new IllegalArgumentException(
                    "executionPermit must belong to the bound Run and Workspace"
            );
        }

        if (sessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "sessionId must not be blank"
            );
        }
        if (taskText.isBlank()) {
            throw new IllegalArgumentException(
                    "taskText must not be blank"
            );
        }
        if (!executionConfig.capabilityPolicy().allows(
                AgentCapability.CODE_READ)) {
            throw new IllegalArgumentException(
                    "Cloud Agent requires CODE_READ"
            );
        }
    }
    public AgentRuntimePolicy runtimePolicy() {
        return executionConfig.policy();
    }

    public AgentCapabilityPolicy capabilities() {
        return executionConfig.capabilityPolicy();
    }
}
