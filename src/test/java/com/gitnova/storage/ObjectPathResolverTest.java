package com.gitnova.storage;

import com.gitnova.gitobject.GitObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectPathResolverTest {

    private static final String SHA1 = "a".repeat(40);

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveObjectsInsideTheNormalizedStorageRoot() {
        Path configuredRoot = tempDir.resolve("storage").resolve("..").resolve("storage");
        ObjectPathResolver resolver = new ObjectPathResolver(configuredRoot);

        Path objectDirectory = resolver.objectDirectory(RepoKey.of(12, 34));
        Path objectPath = resolver.objectPath(
                RepoKey.of(12, 34),
                GitObjectId.of(SHA1)
        );

        Path expectedRoot = tempDir.resolve("storage").toAbsolutePath().normalize();
        assertEquals(
                expectedRoot.resolve("12/34/.gitlet/objects"),
                objectDirectory
        );
        assertEquals(expectedRoot.resolve("12/34/.gitlet/objects").resolve(SHA1), objectPath);
        assertTrue(objectPath.startsWith(expectedRoot));
    }

    @Test
    void shouldKeepRepositoriesInSeparateObjectDirectories() {
        ObjectPathResolver resolver = new ObjectPathResolver(tempDir);

        assertNotEquals(
                resolver.objectDirectory(RepoKey.of(1, 10)),
                resolver.objectDirectory(RepoKey.of(2, 10))
        );
    }
}
