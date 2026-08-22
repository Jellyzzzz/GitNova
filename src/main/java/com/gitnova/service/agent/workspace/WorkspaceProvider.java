package com.gitnova.service.agent.workspace;

public interface WorkspaceProvider {
    WorkspaceHandle provision(WorkspaceSpec trustedSpec);
}
