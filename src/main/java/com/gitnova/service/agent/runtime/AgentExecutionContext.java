package com.gitnova.service.agent.runtime;

import com.gitnova.service.agent.workspace.WorkspaceBinding;

import java.util.Objects;

public record AgentExecutionContext(
        String sessionId,
        AgentRunContext context,
        long actorId,
        String taskText,
        WorkspaceBinding workspace,
        AgentCapabilityPolicy capabilities
) {
    public AgentExecutionContext {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(context);
        Objects.requireNonNull(taskText);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(capabilities);

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
        if (!capabilities.allows(
                AgentCapability.CODE_READ)) {
            throw new IllegalArgumentException(
                    "Cloud Agent requires CODE_READ"
            );
        }
    }
}
