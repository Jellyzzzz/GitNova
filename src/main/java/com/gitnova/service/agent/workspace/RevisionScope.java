package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectId;

public sealed interface RevisionScope permits SnapshotScope, DiffScope {
    GitObjectId baseSha1();
}
