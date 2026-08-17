package com.gitnova.storage;

import com.gitnova.gitobject.GitObjectId;

import java.nio.file.Path;

public final class ObjectPathResolver {

    private final Path storageRoot;

    public ObjectPathResolver(Path storageRoot) {
        this.storageRoot = storageRoot
                .toAbsolutePath()
                .normalize();
    }

    public Path objectDirectory(RepoKey repoKey) {
        Path result = storageRoot
                .resolve(Long.toString(repoKey.ownerId()))
                .resolve(Long.toString(repoKey.repoId()))
                .resolve(".gitlet")
                .resolve("objects")
                .normalize();

        return requireWithinRoot(result);
    }

    public Path objectPath(
            RepoKey repoKey,
            GitObjectId objectId
    ) {
        return requireWithinRoot(
                objectDirectory(repoKey)
                        .resolve(objectId.value())
                        .normalize()
        );
    }

    private Path requireWithinRoot(Path candidate) {
        if (!candidate.startsWith(storageRoot)) {
            throw new IllegalArgumentException(
                    "resolved object path escapes storage root"
            );
        }
        return candidate;
    }
}
