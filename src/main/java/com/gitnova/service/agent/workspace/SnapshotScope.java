package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectId;

import java.util.Objects;

public record SnapshotScope(GitObjectId baseSha1) implements RevisionScope {
    public SnapshotScope{
        Objects.requireNonNull(baseSha1,"baseSha1 must not be null");
    }
    public static SnapshotScope of(String baseSha1){
        return new SnapshotScope(GitObjectId.of(baseSha1));
    }
}
