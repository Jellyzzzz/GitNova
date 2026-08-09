package com.gitnova.gitobject;

import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.storage.FakeObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageGitObjectReaderTest {

    private static final String REPO_KEY = "1/10";

    @Test
    void shouldReturnValidCommitWithExpectedState() {
        FakeObjectStorage storage = new FakeObjectStorage();
        Commit expected = new Commit("add reader", "parent-sha");
        expected.setMapping(Map.of("src/Main.java", "blob-sha"));
        storage.writeObject(REPO_KEY, "commit-sha", Utils.serialize(expected));

        GitObjectReader reader = new ObjectStorageGitObjectReader(storage);

        Commit actual = reader.requireCommit(REPO_KEY, "commit-sha");

        assertNotNull(actual);
        assertEquals("add reader", actual.getMessage());
        assertEquals("parent-sha", actual.getParentCommit());
        assertEquals(Map.of("src/Main.java", "blob-sha"), actual.getMapping());
    }

    @Test
    void shouldReturnExactBlobBytes() {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] expected = {0, 1, 2, -1};
        storage.writeObject(REPO_KEY, "blob-sha", expected);

        GitObjectReader reader = new ObjectStorageGitObjectReader(storage);

        assertArrayEquals(expected, reader.requireBlob(REPO_KEY, "blob-sha"));
    }

    @Test
    void shouldWrapStorageFailureAndPreserveCause() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, "blob-sha", new byte[]{1});
        IllegalStateException storageFailure =
                new IllegalStateException("storage unavailable");
        storage.failReadsWith(storageFailure);

        GitObjectReader reader = new ObjectStorageGitObjectReader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireBlob(REPO_KEY, "blob-sha")
        );

        assertEquals("Failed to read object", exception.getMessage());
        assertEquals(GitObjectReadException.Reason.TRANSIENT, exception.reason());
        assertSame(storageFailure, exception.getCause());
    }

    @Test
    void shouldRejectBytesThatDoNotRepresentACommit() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, "not-a-commit", new byte[]{1, 2, 3});

        GitObjectReader reader = new ObjectStorageGitObjectReader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireCommit(REPO_KEY, "not-a-commit")
        );

        assertEquals("Object is not a valid commit", exception.getMessage());
        assertEquals(GitObjectReadException.Reason.CORRUPT, exception.reason());
        assertNotNull(exception.getCause());
    }

    @Test
    void shouldRejectCommitWithInvalidInternalState() {
        FakeObjectStorage storage = new FakeObjectStorage();
        Commit invalid = new Commit("invalid", null);
        invalid.setMapping(null);
        storage.writeObject(REPO_KEY, "invalid-commit", Utils.serialize(invalid));

        GitObjectReader reader = new ObjectStorageGitObjectReader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireCommit(REPO_KEY, "invalid-commit")
        );

        assertEquals(GitObjectReadException.Reason.CORRUPT, exception.reason());
        assertEquals("Object is not a valid commit", exception.getMessage());
    }

    @Test
    void shouldClassifyAbsentObjectAsNotFound() {
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                new FakeObjectStorage()
        );

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireBlob(REPO_KEY, "missing")
        );

        assertEquals(GitObjectReadException.Reason.NOT_FOUND, exception.reason());
    }

    @Test
    void shouldRejectBlankRepositoryKeyAndSha1() {
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                new FakeObjectStorage()
        );

        IllegalArgumentException repoKeyException = assertThrows(
                IllegalArgumentException.class,
                () -> reader.requireBlob("", "blob-sha")
        );
        IllegalArgumentException sha1Exception = assertThrows(
                IllegalArgumentException.class,
                () -> reader.requireBlob(REPO_KEY, "")
        );

        assertEquals("repoKey must not be blank", repoKeyException.getMessage());
        assertEquals("sha1 must not be blank", sha1Exception.getMessage());
    }
}
