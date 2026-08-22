package com.gitnova.service.agent.workspace;

import com.gitnova.storage.RepoKey;

import java.util.Objects;

public record WorkspaceSpec(WorkspaceId workspaceId,
                            RepoKey repoKey,
                            SnapshotScope snapshotScope) {
    public WorkspaceSpec{
        Objects.requireNonNull(workspaceId,"workspaceId must not be null");
        Objects.requireNonNull(repoKey,"repoKey must not be null");
        Objects.requireNonNull(snapshotScope,"snapshotScope must not be null");
    }
}
