package com.gitnova.storage;

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
    public byte[] readObject(String repoKey, String sha1) {
        if (readFailure != null) {
            throw readFailure;
        }

        byte[] content = objectsByRepo
                .getOrDefault(repoKey, Map.of())
                .get(sha1);

        if (content == null) {
            throw new IllegalStateException("Object not found: " + sha1);
        }

        return Arrays.copyOf(content, content.length);
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
