package com.gitnova.service.agent.workspace;

import java.util.Objects;

public record WorkspaceExecutionPermit(String runId, WorkspaceId workspaceId, long fencingToken) {
    public WorkspaceExecutionPermit{
        Objects.requireNonNull(runId,"runId must not be null");
        Objects.requireNonNull(workspaceId,"workspaceId must not be null");
        if(runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        if (runId.indexOf('\t') >= 0 || runId.indexOf('\n') >= 0 || runId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("runId contains unsupported control characters");
        }
        if(fencingToken<=0) throw new IllegalArgumentException("fencingToken must be not negative");
    }
}
