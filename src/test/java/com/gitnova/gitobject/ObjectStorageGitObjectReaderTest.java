package com.gitnova.gitobject;

import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static com.gitnova.gitobject.GitObjectTestFixtures.commit;
import static com.gitnova.gitobject.GitObjectTestFixtures.encodeCommit;
import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageGitObjectReaderTest {

    private static final String REPO_KEY = "1/10";
    private static final String COMMIT_SHA = objectId('a');
    private static final String PARENT_SHA = objectId('b');
    private static final String BLOB_SHA = objectId('c');
    private static final String MISSING_SHA = objectId('d');
    private static final String INVALID_COMMIT_SHA = objectId('e');

    @Test
    void shouldReturnValidCommitWithExpectedState() {
        FakeObjectStorage storage = new FakeObjectStorage();
        CommitObject expected = commit(
                PARENT_SHA,
                "add reader",
                Map.of("src/Main.java", BLOB_SHA)
        );
        storage.writeObject(REPO_KEY, COMMIT_SHA, encodeCommit(expected));

        GitObjectReader reader = reader(storage);

        CommitObject actual = reader.requireCommit(REPO_KEY, COMMIT_SHA);

        assertNotNull(actual);
        assertEquals("add reader", actual.message());
        assertEquals(PARENT_SHA, actual.parentSha1().orElseThrow().value());
        assertEquals(
                Map.of("src/Main.java", GitObjectId.of(BLOB_SHA)),
                actual.mapping()
        );
    }

    @Test
    void shouldReturnExactBlobBytes() {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] expected = {0, 1, 2, -1};
        storage.writeObject(REPO_KEY, BLOB_SHA, expected);

        GitObjectReader reader = reader(storage);

        assertArrayEquals(expected, reader.requireBlob(REPO_KEY, BLOB_SHA));
    }

    @Test
    void shouldWrapStorageFailureAndPreserveCause() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, BLOB_SHA, new byte[]{1});
        IllegalStateException storageFailure =
                new IllegalStateException("storage unavailable");
        storage.failReadsWith(storageFailure);

        GitObjectReader reader = reader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireBlob(REPO_KEY, BLOB_SHA)
        );

        assertEquals("Failed to read object", exception.getMessage());
        assertEquals(GitObjectReadException.Reason.TRANSIENT, exception.reason());
        assertSame(storageFailure, exception.getCause());
    }

    @Test
    void shouldRejectBytesThatDoNotRepresentACommit() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject(REPO_KEY, INVALID_COMMIT_SHA, new byte[]{1, 2, 3});

        GitObjectReader reader = reader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireCommit(REPO_KEY, INVALID_COMMIT_SHA)
        );

        assertEquals("Object is not a valid canonical commit", exception.getMessage());
        assertEquals(GitObjectReadException.Reason.CORRUPT, exception.reason());
        assertNotNull(exception.getCause());
    }

    @Test
    void shouldRejectCanonicalCommitWithTrailingBytes() {
        FakeObjectStorage storage = new FakeObjectStorage();
        CommitObject valid = commit(null, "valid", Map.of());
        byte[] invalid = Arrays.copyOf(encodeCommit(valid), encodeCommit(valid).length + 1);
        invalid[invalid.length - 1] = 1;
        storage.writeObject(REPO_KEY, INVALID_COMMIT_SHA, invalid);

        GitObjectReader reader = reader(storage);

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireCommit(REPO_KEY, INVALID_COMMIT_SHA)
        );

        assertEquals(GitObjectReadException.Reason.CORRUPT, exception.reason());
        assertEquals("Object is not a valid canonical commit", exception.getMessage());
    }

    @Test
    void shouldClassifyAbsentObjectAsNotFound() {
        GitObjectReader reader = reader(new FakeObjectStorage());

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.requireBlob(REPO_KEY, MISSING_SHA)
        );

        assertEquals(GitObjectReadException.Reason.NOT_FOUND, exception.reason());
    }

    @Test
    void shouldRejectBlankRepositoryKeyAndSha1() {
        GitObjectReader reader = reader(new FakeObjectStorage());

        IllegalArgumentException repoKeyException = assertThrows(
                IllegalArgumentException.class,
                () -> reader.requireBlob("", BLOB_SHA)
        );
        IllegalArgumentException sha1Exception = assertThrows(
                IllegalArgumentException.class,
                () -> reader.requireBlob(REPO_KEY, "")
        );

        assertEquals("repoKey must not be blank", repoKeyException.getMessage());
        assertEquals("sha1 must not be blank", sha1Exception.getMessage());
    }

    @Test
    void shouldStreamBlobWithoutCallingBufferedRead() throws IOException {
        byte[] expected = new byte[150_000];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index % 251);
        }
        String sha1 = GitObjectHasher.sha1(expected).value();
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.openObject(REPO_KEY, sha1))
                .thenReturn(new ByteArrayInputStream(expected));
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                storage,
                new CanonicalGitObjectCodec()
        );
        ByteArrayOutputStream destination = new ByteArrayOutputStream();

        long copied = reader.copyBlobTo(REPO_KEY, sha1, destination);

        assertEquals(expected.length, copied);
        assertArrayEquals(expected, destination.toByteArray());
        verify(storage).openObject(REPO_KEY, sha1);
        verify(storage, never()).readObject(anyString(), anyString());
    }

    @Test
    void shouldRejectStreamWhoseBytesDoNotMatchObjectAddress() throws IOException {
        byte[] corrupt = "corrupt object".getBytes(StandardCharsets.UTF_8);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.openObject(REPO_KEY, BLOB_SHA))
                .thenReturn(new ByteArrayInputStream(corrupt));
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                storage,
                new CanonicalGitObjectCodec()
        );

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.copyBlobTo(
                        REPO_KEY,
                        BLOB_SHA,
                        new ByteArrayOutputStream()
                )
        );

        assertEquals(GitObjectReadException.Reason.CORRUPT, exception.reason());
    }

    @Test
    void shouldMapSourceStreamFailureToTransientReadFailure() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.openObject(REPO_KEY, BLOB_SHA))
                .thenReturn(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("source unavailable");
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length)
                            throws IOException {
                        throw new IOException("source unavailable");
                    }
                });
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                storage,
                new CanonicalGitObjectCodec()
        );

        GitObjectReadException exception = assertThrows(
                GitObjectReadException.class,
                () -> reader.copyBlobTo(
                        REPO_KEY,
                        BLOB_SHA,
                        new ByteArrayOutputStream()
                )
        );

        assertEquals(GitObjectReadException.Reason.TRANSIENT, exception.reason());
        assertEquals("source unavailable", exception.getCause().getMessage());
    }

    @Test
    void shouldKeepDestinationWriteFailureAsIOException() {
        byte[] content = "valid object".getBytes(StandardCharsets.UTF_8);
        String sha1 = GitObjectHasher.sha1(content).value();
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.openObject(REPO_KEY, sha1))
                .thenReturn(new ByteArrayInputStream(content));
        GitObjectReader reader = new ObjectStorageGitObjectReader(
                storage,
                new CanonicalGitObjectCodec()
        );
        OutputStream failingDestination = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("workspace disk full");
            }

            @Override
            public void write(byte[] buffer, int offset, int length)
                    throws IOException {
                throw new IOException("workspace disk full");
            }
        };

        IOException exception = assertThrows(
                IOException.class,
                () -> reader.copyBlobTo(REPO_KEY, sha1, failingDestination)
        );

        assertEquals("workspace disk full", exception.getMessage());
    }
}
