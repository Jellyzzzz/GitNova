package com.gitnova.storage;

import com.gitnova.storage.config.RepositoryStorageProperties;
import com.gitnova.gitobject.GitObjectHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalObjectStorageTest {

    private static final String REPO_KEY = "1/10";
    private static final String FIRST_SHA1 = "a".repeat(40);
    private static final String SECOND_SHA1 = "b".repeat(40);

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateTheObjectDirectoryAndRoundTripObjectBytes() throws IOException {
        LocalObjectStorage storage = storage();
        byte[] expected = "GitNova object".getBytes(StandardCharsets.UTF_8);
        String expectedSha1 = GitObjectHasher.sha1(expected).value();

        storage.writeObject(REPO_KEY, expectedSha1, expected);

        assertTrue(storage.existsObject(REPO_KEY, expectedSha1));
        assertArrayEquals(expected, storage.readObject(REPO_KEY, expectedSha1));
    }

    @Test
    void shouldListOnlyDirectRegularFilesWithValidObjectIds() throws IOException {
        LocalObjectStorage storage = storage();
        Path objectDirectory = objectDirectory();
        Files.createDirectories(objectDirectory);
        Files.writeString(objectDirectory.resolve(FIRST_SHA1), "first");
        Files.writeString(objectDirectory.resolve(SECOND_SHA1), "second");
        Files.writeString(objectDirectory.resolve("incomplete-upload.tmp"), "temporary");
        Files.createDirectory(objectDirectory.resolve("nested-directory"));

        Set<String> listed = storage.listObjects(REPO_KEY);

        assertEquals(Set.of(FIRST_SHA1, SECOND_SHA1), listed);
    }

    @Test
    void shouldReturnAnEmptySetForAnUninitializedRepositoryWithoutCreatingDirectories()
            throws IOException {
        LocalObjectStorage storage = storage();
        Path objectDirectory = objectDirectory();
        assertFalse(Files.exists(objectDirectory));

        assertEquals(Set.of(), storage.listObjects(REPO_KEY));

        assertFalse(Files.exists(objectDirectory));
    }

    @Test
    void shouldRejectMalformedRepositoryKeysAndObjectIdsBeforeFilesystemAccess() {
        LocalObjectStorage storage = storage();

        assertThrows(
                IllegalArgumentException.class,
                () -> storage.writeObject("../1", FIRST_SHA1, new byte[]{1})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.writeObject(REPO_KEY, "not-a-sha", new byte[]{1})
        );
        assertFalse(Files.exists(tempDir.resolve("1")));
    }

    @Test
    void shouldClassifyMissingObjectWithoutInventingAnIoCause() {
        ObjectStorageException exception = assertThrows(
                ObjectStorageException.class,
                () -> storage().readObject(REPO_KEY, FIRST_SHA1)
        );

        assertEquals(ObjectStorageException.Reason.NOT_FOUND, exception.reason());
        assertNull(exception.getCause());
    }

    @Test
    void shouldRejectAStorageRootThatIsNotADirectory() throws IOException {
        Path fileInsteadOfStorageRoot = tempDir.resolve("not-a-directory");
        Files.writeString(fileInsteadOfStorageRoot, "file");
        LocalObjectStorage storage = new LocalObjectStorage(
                new RepositoryStorageProperties(fileInsteadOfStorageRoot)
        );

        byte[] content = new byte[]{1};
        String contentSha1 = GitObjectHasher.sha1(content).value();
        ObjectStorageException exception = assertThrows(
                ObjectStorageException.class,
                () -> storage.writeObject(REPO_KEY, contentSha1, content)
        );

        assertEquals(ObjectStorageException.Reason.CORRUPT, exception.reason());
        assertNull(exception.getCause());
    }

    private LocalObjectStorage storage() {
        return new LocalObjectStorage(new RepositoryStorageProperties(tempDir));
    }

    private Path objectDirectory() {
        return new ObjectPathResolver(tempDir)
                .objectDirectory(RepoKey.of(1, 10));
    }
}
