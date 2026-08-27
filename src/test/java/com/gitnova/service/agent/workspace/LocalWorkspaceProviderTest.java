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
import com.gitnova.storage.config.WorkspaceStorageProperties;
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

class LocalWorkspaceProviderTest {

    private static final RepoKey REPO_KEY = RepoKey.of(7L, 42L);

    @TempDir
    Path tempDir;

    @Test
    void shouldPublishReadyWorkspaceFromCanonicalSnapshot() throws IOException {
        byte[] app = "package example;\nclass App {}\n"
                .getBytes(StandardCharsets.UTF_8);
        Fixture fixture = fixture(Map.of("src/main/java/example/App.java", app));
        WorkspaceId workspaceId = WorkspaceId.generate();

        WorkspaceHandle handle = fixture.provider().provision(
                spec(workspaceId, fixture.commitId())
        );

        Path expectedRoot = fixture.workspaceBase().resolve(workspaceId.toString());
        assertEquals(WorkspaceStatus.READY, handle.status());
        assertEquals(0, handle.generation());
        assertEquals(expectedRoot, handle.root());
        assertArrayEquals(
                app,
                Files.readAllBytes(
                        expectedRoot.resolve("src/main/java/example/App.java")
                )
        );
        assertNoStagingDirectories(fixture.workspaceBase());
    }

    @Test
    void shouldIdempotentlyReuseAnAlreadyPublishedEquivalentWorkspace() throws IOException {
        byte[] app = "class App {}\n".getBytes(StandardCharsets.UTF_8);
        Fixture fixture = fixture(Map.of("src/App.java", app));
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkspaceSpec spec = spec(workspaceId, fixture.commitId());

        WorkspaceHandle first = fixture.provider().provision(spec);
        WorkspaceHandle retry = fixture.provider().provision(spec);

        assertEquals(first.root(), retry.root());
        assertEquals(WorkspaceStatus.READY, retry.status());
        assertArrayEquals(app, Files.readAllBytes(retry.root().resolve("src/App.java")));
        assertNoStagingDirectories(fixture.workspaceBase());
    }

    @Test
    void shouldRejectExistingWorkspaceWithoutOverwritingIt() throws IOException {
        Fixture fixture = fixture(Map.of(
                "src/App.java", "class App {}\n".getBytes(StandardCharsets.UTF_8)
        ));
        WorkspaceId workspaceId = WorkspaceId.generate();
        Path finalRoot = Files.createDirectories(
                fixture.workspaceBase().resolve(workspaceId.toString())
        );
        Path sentinel = Files.writeString(finalRoot.resolve("keep.txt"), "keep");

        WorkspaceProvisionException exception = assertThrows(
                WorkspaceProvisionException.class,
                () -> fixture.provider().provision(
                        spec(workspaceId, fixture.commitId())
                )
        );

        assertEquals(
                WorkspaceProvisionException.Reason.WORKSPACE_CONFLICT,
                exception.reason()
        );
        assertEquals("keep", Files.readString(sentinel));
        assertNoStagingDirectories(fixture.workspaceBase());
    }

    @Test
    void shouldRemoveStagingTreeWhenSnapshotBlobIsMissing() throws IOException {
        GitObjectId missingBlob = GitObjectId.of("f".repeat(40));
        Fixture fixture = fixtureWithMapping(Map.of("nested/App.java", missingBlob));
        WorkspaceId workspaceId = WorkspaceId.generate();

        WorkspaceProvisionException exception = assertThrows(
                WorkspaceProvisionException.class,
                () -> fixture.provider().provision(
                        spec(workspaceId, fixture.commitId())
                )
        );

        assertEquals(
                WorkspaceProvisionException.Reason.SNAPSHOT_NOT_FOUND,
                exception.reason()
        );
        assertFalse(Files.exists(fixture.workspaceBase().resolve(workspaceId.toString())));
        assertNoStagingDirectories(fixture.workspaceBase());
    }

    @Test
    void shouldIsolateTwoWorkspacesCreatedFromSameSnapshot() throws IOException {
        byte[] original = "class App {}\n".getBytes(StandardCharsets.UTF_8);
        Fixture fixture = fixture(Map.of("src/App.java", original));
        WorkspaceHandle first = fixture.provider().provision(
                spec(WorkspaceId.generate(), fixture.commitId())
        );
        WorkspaceHandle second = fixture.provider().provision(
                spec(WorkspaceId.generate(), fixture.commitId())
        );

        Files.writeString(first.root().resolve("src/App.java"), "class Changed {}\n");

        assertEquals("class Changed {}\n", Files.readString(first.root().resolve("src/App.java")));
        assertArrayEquals(original, Files.readAllBytes(second.root().resolve("src/App.java")));
        assertFalse(first.root().equals(second.root()));
    }

    private WorkspaceSpec spec(WorkspaceId workspaceId, GitObjectId commitId) {
        return new WorkspaceSpec(
                workspaceId,
                REPO_KEY,
                new SnapshotScope(commitId)
        );
    }

    private Fixture fixture(Map<String, byte[]> files) {
        ObjectStorage storage = storage();
        Map<String, GitObjectId> mapping = new LinkedHashMap<>();
        files.forEach((path, content) -> mapping.put(path, writeObject(storage, content)));
        return fixture(storage, mapping);
    }

    private Fixture fixtureWithMapping(Map<String, GitObjectId> mapping) {
        return fixture(storage(), mapping);
    }

    private Fixture fixture(ObjectStorage storage, Map<String, GitObjectId> mapping) {
        CanonicalGitObjectCodec codec = new CanonicalGitObjectCodec();
        CommitObject commit = new CommitObject(
                Optional.empty(),
                Instant.parse("2026-08-22T00:00:00Z"),
                "local workspace provider fixture",
                mapping
        );
        GitObjectId commitId = writeObject(storage, codec.encodeCommit(commit));
        Path workspaceBase = tempDir.resolve("workspaces");
        WorkspaceMaterializer materializer = new WorkspaceMaterializer(
                new ObjectStorageGitObjectReader(storage, codec)
        );
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                new WorkspaceStorageProperties(workspaceBase),
                materializer
        );
        return new Fixture(commitId, workspaceBase, provider);
    }

    private ObjectStorage storage() {
        return new LocalObjectStorage(
                new RepositoryStorageProperties(tempDir.resolve("objects"))
        );
    }

    private GitObjectId writeObject(ObjectStorage storage, byte[] content) {
        GitObjectId id = GitObjectHasher.sha1(content);
        storage.writeObject(REPO_KEY.value(), id.value(), content);
        return id;
    }

    private void assertNoStagingDirectories(Path workspaceBase) throws IOException {
        if (Files.notExists(workspaceBase)) {
            return;
        }
        try (var entries = Files.list(workspaceBase)) {
            assertTrue(entries.noneMatch(path ->
                    path.getFileName().toString().startsWith(".provisioning-")));
        }
    }

    private record Fixture(
            GitObjectId commitId,
            Path workspaceBase,
            LocalWorkspaceProvider provider
    ) {
    }
}
