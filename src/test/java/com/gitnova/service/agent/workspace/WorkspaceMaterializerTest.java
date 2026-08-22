package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.CanonicalGitObjectCodec;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.storage.LocalObjectStorage;
import com.gitnova.storage.ObjectStorage;
import com.gitnova.storage.RepoKey;
import com.gitnova.storage.config.RepositoryStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMaterializerTest {

    private static final RepoKey REPO_KEY = RepoKey.of(7L, 42L);

    @TempDir
    Path tempDir;

    @Test
    void shouldMaterializeCanonicalCommitIntoRealFileTree() throws IOException {
        Fixture fixture = fixture(Map.of(
                "README.md", "# GitNova\n".getBytes(StandardCharsets.UTF_8),
                "src/main/java/example/App.java",
                "package example;\npublic final class App {}\n"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        Path stagingRoot = Files.createDirectory(tempDir.resolve("staging"));

        fixture.materializer().materialize(
                REPO_KEY,
                stagingRoot,
                new SnapshotScope(fixture.commitId())
        );

        assertArrayEquals(
                "# GitNova\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(stagingRoot.resolve("README.md"))
        );
        assertArrayEquals(
                "package example;\npublic final class App {}\n"
                        .getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(
                        stagingRoot.resolve("src/main/java/example/App.java")
                )
        );
        assertTrue(Files.isDirectory(stagingRoot.resolve("src/main/java/example")));
    }

    @Test
    void shouldUseTheNormalizedRootThatWasValidated() throws IOException {
        Fixture fixture = fixture(Map.of(
                "src/App.java", "class App {}\n".getBytes(StandardCharsets.UTF_8)
        ));
        Path stagingRoot = Files.createDirectory(tempDir.resolve("normalized-staging"));
        Path relativeRoot = Path.of("")
                .toAbsolutePath()
                .relativize(stagingRoot);

        fixture.materializer().materialize(
                REPO_KEY,
                relativeRoot,
                new SnapshotScope(fixture.commitId())
        );

        assertTrue(Files.isRegularFile(stagingRoot.resolve("src/App.java")));
    }

    @Test
    void shouldRejectNonEmptyStagingRootWithoutChangingExistingContent()
            throws IOException {
        Fixture fixture = fixture(Map.of(
                "src/App.java", "class App {}\n".getBytes(StandardCharsets.UTF_8)
        ));
        Path stagingRoot = Files.createDirectory(tempDir.resolve("non-empty"));
        Path existing = Files.writeString(stagingRoot.resolve("unexpected.txt"), "keep");

        WorkspaceProvisionException exception = assertThrows(
                WorkspaceProvisionException.class,
                () -> fixture.materializer().materialize(
                        REPO_KEY,
                        stagingRoot,
                        new SnapshotScope(fixture.commitId())
                )
        );

        assertEquals(
                WorkspaceProvisionException.Reason.WORKSPACE_CONFLICT,
                exception.reason()
        );
        assertEquals("keep", Files.readString(existing));
        assertFalse(Files.exists(stagingRoot.resolve("src/App.java")));
    }

    @Test
    void shouldRejectFileDirectoryCollisionBeforeWritingAnyBlob()
            throws IOException {
        Fixture fixture = fixture(Map.of(
                "a", "file\n".getBytes(StandardCharsets.UTF_8),
                "a/child.txt", "child\n".getBytes(StandardCharsets.UTF_8)
        ));
        Path stagingRoot = Files.createDirectory(tempDir.resolve("collision"));

        WorkspaceProvisionException exception = assertThrows(
                WorkspaceProvisionException.class,
                () -> fixture.materializer().materialize(
                        REPO_KEY,
                        stagingRoot,
                        new SnapshotScope(fixture.commitId())
                )
        );

        assertEquals(
                WorkspaceProvisionException.Reason.PATH_COLLISION,
                exception.reason()
        );
        try (var entries = Files.list(stagingRoot)) {
            assertEquals(0L, entries.count());
        }
    }

    private Fixture fixture(Map<String, byte[]> files) {
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        ObjectStorage storage = new LocalObjectStorage(
                new RepositoryStorageProperties(tempDir.resolve("objects"))
        );
        Map<String, GitObjectId> mapping = new LinkedHashMap<>();
        files.forEach((path, content) -> {
            GitObjectId blobId = writeObject(storage, content);
            mapping.put(path, blobId);
        });
        CommitObject commit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-08-22T00:00:00Z"),
                "workspace materializer fixture",
                mapping
        );
        GitObjectId commitId = writeObject(storage, codec.encodeCommit(commit));
        WorkspaceMaterializer materializer = new WorkspaceMaterializer(
                new ObjectStorageGitObjectReader(storage, codec)
        );
        return new Fixture(commitId, materializer);
    }

    private GitObjectId writeObject(ObjectStorage storage, byte[] content) {
        GitObjectId id = GitObjectHasher.sha1(content);
        storage.writeObject(REPO_KEY.value(), id.value(), content);
        return id;
    }

    private record Fixture(
            GitObjectId commitId,
            WorkspaceMaterializer materializer
    ) {
    }
}
