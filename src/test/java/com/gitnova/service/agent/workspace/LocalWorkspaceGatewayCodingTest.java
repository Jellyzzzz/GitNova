package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceGatewayCodingTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
    private static final String MAIN_BLOB = objectId('b');
    private static final String README_BLOB = objectId('c');

    @TempDir
    Path tempDir;

    @Test
    void shouldBrowseFindSearchAndReadOneWorkspaceGeneration() throws Exception {
        Fixture fixture = fixture(null);
        Files.createDirectories(fixture.root().resolve(".gitnova"));
        Files.writeString(fixture.root().resolve(".gitnova/internal.txt"), "hidden needle");

        WorkspaceGateway.FileListing listing = fixture.gateway().listFiles(
                fixture.workspaceId(),
                "."
        );
        WorkspaceGateway.FileSearch files = fixture.gateway().findFiles(
                fixture.workspaceId(),
                "**/*.java"
        );
        WorkspaceGateway.TextSearch search = fixture.gateway().searchText(
                fixture.workspaceId(),
                "needle",
                true
        );
        WorkspaceGateway.FileContent content = fixture.gateway().readFile(
                fixture.workspaceId(),
                "src/Main.java",
                1,
                10
        );

        assertEquals(0, listing.generation());
        assertEquals(List.of("README.md", "src"), listing.entries().stream()
                .map(WorkspaceGateway.FileEntry::path)
                .toList());
        assertEquals(List.of("src/Main.java"), files.paths());
        assertEquals(1, search.matches().size());
        assertEquals("src/Main.java", search.matches().get(0).filePath());
        assertEquals(2, search.matches().get(0).lineNumber());
        assertEquals(3, content.totalLines());
        assertEquals("class Main {", content.lines().get(0).content());
    }

    @Test
    void shouldComputeAuthoritativeBaseToWorkspaceDiff() throws Exception {
        Fixture fixture = fixture(null);
        Files.writeString(
                fixture.root().resolve("src/Main.java"),
                "class Main {\n    String value = \"after\";\n}\n"
        );
        Files.delete(fixture.root().resolve("README.md"));
        Files.writeString(fixture.root().resolve("src/New.java"), "class New {}\n");

        WorkspaceGateway.WorkspaceDiff diff = fixture.gateway().getWorkspaceDiff(
                fixture.workspaceId()
        );

        assertEquals(0, diff.generation());
        assertEquals(3, diff.files().size());
        assertEquals(
                List.of(
                        WorkspaceGateway.DiffChangeType.DELETED,
                        WorkspaceGateway.DiffChangeType.MODIFIED,
                        WorkspaceGateway.DiffChangeType.ADDED
                ),
                diff.files().stream().map(WorkspaceGateway.DiffFile::changeType).toList()
        );
        assertTrue(diff.unifiedDiff().contains("--- a/src/Main.java"));
        assertTrue(diff.unifiedDiff().contains("+    String value = \"after\";"));
        assertFalse(diff.containsBinary());
    }

    @Test
    void shouldSerializeCommandMutationAndAdvanceGenerationWhenTreeChanges() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) -> {
            executions.incrementAndGet();
            Files.writeString(workingDirectory.resolve("Generated.java"), "class Generated {}\n");
            return new WorkspaceCommandExecutor.ProcessResult(
                    false,
                    0,
                    25,
                    "tests passed",
                    ""
            );
        };
        Fixture fixture = fixture(executor);
        WorkspaceGateway.CommandRequest request = new WorkspaceGateway.CommandRequest(
                0,
                List.of("test-runner", "focused"),
                "src",
                30,
                "run focused tests"
        );

        WorkspaceGateway.CommandResult first = fixture.gateway().runCommand(
                fixture.workspaceId(),
                request
        );
        WorkspaceGateway.CommandResult stale = fixture.gateway().runCommand(
                fixture.workspaceId(),
                request
        );

        assertEquals(WorkspaceGateway.CommandStatus.COMPLETED, first.status());
        assertEquals(0, first.generationBefore());
        assertEquals(1, first.generationAfter());
        assertTrue(first.stateChanged());
        assertEquals("tests passed", first.stdout());
        assertEquals(WorkspaceGateway.CommandStatus.CONFLICT, stale.status());
        assertEquals(1, executions.get());
        assertEquals(1, fixture.state().generation());
    }

    @Test
    void shouldTreatNonZeroCommandExitAsObservedCommandOutcome() {
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) ->
                new WorkspaceCommandExecutor.ProcessResult(
                        false,
                        1,
                        10,
                        "",
                        "test failure"
                );
        Fixture fixture = fixture(executor);

        WorkspaceGateway.CommandResult result = fixture.gateway().runCommand(
                fixture.workspaceId(),
                new WorkspaceGateway.CommandRequest(
                        0,
                        List.of("test-runner"),
                        ".",
                        30,
                        "run tests"
                )
        );

        assertEquals(WorkspaceGateway.CommandStatus.COMPLETED, result.status());
        assertEquals(1, result.exitCode());
        assertEquals(0, result.generationAfter());
        assertFalse(result.stateChanged());
    }

    private Fixture fixture(WorkspaceCommandExecutor executor) {
        try {
            FakeObjectStorage storage = new FakeObjectStorage();
            byte[] main = "class Main {\n    String value = \"needle\";\n}\n"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] readme = "base readme\n".getBytes(StandardCharsets.UTF_8);
            storage.writeObject(REPO_KEY, MAIN_BLOB, main);
            storage.writeObject(REPO_KEY, README_BLOB, readme);
            writeCommit(
                    storage,
                    REPO_KEY,
                    BASE_SHA,
                    null,
                    "base",
                    Map.of(
                            "src/Main.java", MAIN_BLOB,
                            "README.md", README_BLOB
                    )
            );

            Path root = Files.createDirectories(tempDir.resolve("workspace-" + System.nanoTime()));
            Files.createDirectories(root.resolve("src"));
            Files.write(root.resolve("src/Main.java"), main);
            Files.write(root.resolve("README.md"), readme);

            WorkspaceId workspaceId = WorkspaceId.generate();
            WorkspaceHandle handle = new WorkspaceHandle(
                    workspaceId,
                    RepoKey.parseCanonical(REPO_KEY),
                    SnapshotScope.of(BASE_SHA),
                    root,
                    WorkspaceStatus.READY,
                    0
            );
            LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry();
            registry.register(handle);
            GitObjectReader objectReader = reader(storage);
            return new Fixture(
                    workspaceId,
                    root,
                    registry.require(workspaceId),
                    new LocalWorkspaceGateway(registry, objectReader, executor),
                    objectReader
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            WorkspaceId workspaceId,
            Path root,
            LocalWorkspaceRegistry.LocalWorkspaceState state,
            LocalWorkspaceGateway gateway,
            GitObjectReader objectReader
    ) {
    }
}
