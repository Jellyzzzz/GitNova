package com.gitnova.service.agent.workspace;

import java.util.Objects;

/**
 * Trusted logical binding between a tool execution and its Session-scoped Workspace.
 *
 * <p>The binding intentionally contains no host path or provider reference. Those details
 * remain internal to {@link WorkspaceProvider} and the future WorkspaceGateway.</p>
 */
public record WorkspaceBinding(WorkspaceId workspaceId) {

    public WorkspaceBinding {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    }
}
