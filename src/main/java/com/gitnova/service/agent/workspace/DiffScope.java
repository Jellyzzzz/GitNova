package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectId;

import java.util.Objects;

public record DiffScope(
        GitObjectId baseSha1,
        GitObjectId targetSha1
) implements RevisionScope {
    public DiffScope {
        Objects.requireNonNull(baseSha1, "baseSha1 must not be null");
        Objects.requireNonNull(targetSha1, "targetSha1 must not be null");
    }

    public static DiffScope of(String base, String target) {
        return new DiffScope(
                GitObjectId.of(base),
                GitObjectId.of(target)
        );
    }
}
