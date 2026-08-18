package com.gitnova.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Owns the temporary files created by a successful decoder invocation. */
public final class ValidatedPack implements AutoCloseable {

    private final Path stagingDirectory;
    private final List<ValidatedObject> objects;

    ValidatedPack(Path stagingDirectory, List<ValidatedObject> objects) {
        this.stagingDirectory = stagingDirectory;
        this.objects = List.copyOf(objects);
    }

    public List<ValidatedObject> objects() {
        return objects;
    }

    @Override
    public void close() {
        try {
            if (Files.exists(stagingDirectory)) {
                try (var paths = Files.walk(stagingDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
                }
            }
        } catch (IOException ignored) {
            // Staging files are unreachable; a subsequent bounded cleanup can retry them.
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup on the failure path.
        }
    }
}
