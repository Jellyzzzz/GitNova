package com.gitnova.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public class FakeObjectStorage implements ObjectStorage {
    private final Map<String, Map<String, byte[]>> objectsByRepo =
            new HashMap<>();
    private RuntimeException readFailure;

    public void failReadsWith(RuntimeException exception) {
        this.readFailure = Objects.requireNonNull(exception);
    }

    @Override
    public void writeObject(String repoKey, String sha1, byte[] content) {
        objectsByRepo
                .computeIfAbsent(repoKey, ignored -> new HashMap<>())
                .put(sha1, Arrays.copyOf(content, content.length));
    }

    @Override
    public void promoteObject(String repoKey, String sha1, Path verifiedTemporaryFile) {
        try {
            writeObject(repoKey, sha1, Files.readAllBytes(verifiedTemporaryFile));
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    ObjectStorageException.Reason.TRANSIENT,
                    "Failed to promote fake object",
                    exception
            );
        }
    }

    @Override
    public byte[] readObject(String repoKey, String sha1) {
        if (readFailure != null) {
            throw readFailure;
        }

        byte[] content = objectsByRepo
                .getOrDefault(repoKey, Map.of())
                .get(sha1);

        if (content == null) {
            throw new ObjectStorageException(
                    ObjectStorageException.Reason.NOT_FOUND,
                    "Object not found: " + sha1
            );
        }

        return Arrays.copyOf(content, content.length);
    }

    @Override
    public InputStream openObject(String repoKey, String sha1) {
        return new ByteArrayInputStream(readObject(repoKey, sha1));
    }

    @Override
    public boolean existsObject(String repoKey, String sha1) {
        return objectsByRepo
                .getOrDefault(repoKey, Map.of())
                .containsKey(sha1);
    }

    @Override
    public Set<String> listObjects(String repoKey) {
        return Set.copyOf(
                objectsByRepo
                        .getOrDefault(repoKey, Map.of())
                        .keySet()
        );
    }
}
