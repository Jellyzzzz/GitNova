package com.gitnova.service.agent.workspace;

import com.gitnova.storage.RepoKey;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceHandle(WorkspaceId workspaceId,
                              RepoKey repoKey,
                              SnapshotScope source,
                              Path root,
                              WorkspaceStatus status,
                              long generation) {
    public WorkspaceHandle{
        Objects.requireNonNull(workspaceId,"workspaceId must not be null");
        Objects.requireNonNull(repoKey,"repoKey must not be null");
        Objects.requireNonNull(source,"source must not be null");
        Objects.requireNonNull(root,"root must not be null");
        Objects.requireNonNull(status,"status must not be null");
        if(generation<0) throw new IllegalArgumentException("generation must not be negative");
        root=root.toAbsolutePath().normalize();
    }
}
