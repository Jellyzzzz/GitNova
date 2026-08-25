package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.storage.FakeObjectStorage;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gitnova.gitobject.GitObjectTestFixtures.objectId;
import static com.gitnova.gitobject.GitObjectTestFixtures.reader;
import static com.gitnova.gitobject.GitObjectTestFixtures.writeCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceGatewayCodingTest {

    private static final String REPO_KEY = "1/10";
    private static final String BASE_SHA = objectId('a');
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
    void shouldBoundSearchResultsAndWorkspaceReadOutput() throws Exception {
        Fixture fixture = fixture(null);
        StringBuilder matches = new StringBuilder();
        for (int index = 0; index < WorkspaceGateway.MAX_SEARCH_RESULTS + 1; index++) {
            matches.append("needle-").append(index).append('\n');
        }
        Files.writeString(fixture.root().resolve("src/Matches.txt"), matches);
        Files.writeString(
                fixture.root().resolve("src/HugeLine.txt"),
                "x".repeat(WorkspaceGateway.MAX_READ_OUTPUT_BYTES + 1)
        );

        WorkspaceGateway.TextSearch search = fixture.gateway().searchText(
                fixture.workspaceId(),
                "needle",
                true
        );
        WorkspaceOperationException readFailure = assertThrows(
                WorkspaceOperationException.class,
                () -> fixture.gateway().readFile(
                        fixture.workspaceId(),
                        "src/HugeLine.txt",
                        1,
                        1
                )
        );

        assertEquals(WorkspaceGateway.MAX_SEARCH_RESULTS, search.matches().size());
        assertTrue(search.truncated());
        assertEquals("READ_OUTPUT_TOO_LARGE", readFailure.errorCode());
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
    void shouldReturnNotFoundWhenFileWasExternallyDeletedBeforeRead() throws Exception {
        Fixture fixture = fixture(null);
        Files.delete(fixture.root().resolve("src/Main.java"));

        WorkspaceOperationException exception = assertThrows(
                WorkspaceOperationException.class,
                () -> fixture.gateway().readFile(
                        fixture.workspaceId(),
                        "src/Main.java",
                        1,
                        10
                )
        );

        assertEquals("FILE_NOT_FOUND", exception.errorCode());
    }

    @Test
    void shouldExposeExternallyModifiedFileAndDiffAtRefreshedGeneration() throws Exception {
        Fixture fixture = fixture(null);
        Files.writeString(
                fixture.root().resolve("src/Main.java"),
                "class Main {\n    String value = \"latest\";\n}\n"
        );

        WorkspaceGateway.WorkspaceRefresh refresh = fixture.gateway().refreshWorkspace(
                fixture.workspaceId()
        );
        WorkspaceGateway.FileContent content = fixture.gateway().readFile(
                fixture.workspaceId(),
                "src/Main.java",
                1,
                10
        );
        WorkspaceGateway.WorkspaceDiff diff = fixture.gateway().getWorkspaceDiff(
                fixture.workspaceId()
        );

        assertTrue(refresh.changed());
        assertEquals(1, refresh.generationAfter());
        assertEquals(1, content.generation());
        assertEquals(1, diff.generation());
        assertTrue(content.lines().stream().anyMatch(line -> line.content().contains("latest")));
        assertTrue(diff.unifiedDiff().contains("+    String value = \"latest\";"));
    }

    @Test
    void shouldNeverReturnPartialSuccessWhenExternalDeleteRacesWithRead() throws Exception {
        Fixture fixture = fixture(null);
        Path target = fixture.root().resolve("src/Racing.txt");
        StringBuilder contentBuilder = new StringBuilder();
        for (int line = 0; line < 50_000; line++) {
            contentBuilder.append("line-").append(line).append('\n');
        }
        String completeContent = contentBuilder.toString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                Files.writeString(target, completeContent);
                CountDownLatch readSubmitted = new CountDownLatch(1);
                Future<WorkspaceGateway.FileContent> read = pool.submit(() -> {
                    readSubmitted.countDown();
                    return fixture.gateway().readFile(
                            fixture.workspaceId(),
                            "src/Racing.txt",
                            1,
                            1
                    );
                });
                assertTrue(readSubmitted.await(1, TimeUnit.SECONDS));
                Files.deleteIfExists(target);

                try {
                    WorkspaceGateway.FileContent result = read.get(2, TimeUnit.SECONDS);
                    assertEquals(50_000, result.totalLines());
                    assertEquals("line-0", result.lines().get(0).content());
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof WorkspaceOperationException);
                    WorkspaceOperationException failure =
                            (WorkspaceOperationException) exception.getCause();
                    assertTrue(
                            "FILE_NOT_FOUND".equals(failure.errorCode())
                                    || "WORKSPACE_FILE_READ_FAILED".equals(failure.errorCode()),
                            failure.errorCode()
                    );
                }
            }
        } finally {
            pool.shutdownNow();
        }
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
                    "",
                    false,
                    false
            );
        };
        Fixture fixture = fixture(executor);
        WorkspaceGateway.CommandRequest request = new WorkspaceGateway.CommandRequest(
                0,
                List.of("test-runner", "focused"),
                "src",
                30,
                "context focused tests"
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
                        "test failure",
                        false,
                        false
                );
        Fixture fixture = fixture(executor);

        WorkspaceGateway.CommandResult result = fixture.gateway().runCommand(
                fixture.workspaceId(),
                new WorkspaceGateway.CommandRequest(
                        0,
                        List.of("test-runner"),
                        ".",
                        30,
                        "context tests"
                )
        );

        assertEquals(WorkspaceGateway.CommandStatus.COMPLETED, result.status());
        assertEquals(1, result.exitCode());
        assertEquals(0, result.generationAfter());
        assertFalse(result.stateChanged());
    }

    @Test
    void shouldRejectEscapingCommandDirectoryBeforeInvokingExecutor() {
        AtomicInteger executions = new AtomicInteger();
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) -> {
            executions.incrementAndGet();
            throw new AssertionError("escaping command must not execute");
        };
        Fixture fixture = fixture(executor);

        WorkspaceOperationException exception = assertThrows(
                WorkspaceOperationException.class,
                () -> fixture.gateway().runCommand(
                        fixture.workspaceId(),
                        new WorkspaceGateway.CommandRequest(
                                0,
                                List.of("test-runner"),
                                "../outside",
                                30,
                                "must be rejected"
                        )
                )
        );

        assertEquals("INVALID_WORKSPACE_PATH", exception.errorCode());
        assertEquals(0, executions.get());
        assertEquals(0, fixture.state().generation());
    }

    @Test
    void shouldBoundCommandStreamsAndPreserveTruncationState() {
        String oversized = "x".repeat(WorkspaceGateway.MAX_COMMAND_STREAM_BYTES + 100);
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) ->
                new WorkspaceCommandExecutor.ProcessResult(
                        true,
                        null,
                        30_000,
                        oversized,
                        oversized,
                        false,
                        false
                );
        Fixture fixture = fixture(executor);

        WorkspaceGateway.CommandResult result = fixture.gateway().runCommand(
                fixture.workspaceId(),
                new WorkspaceGateway.CommandRequest(
                        0,
                        List.of("test-runner"),
                        ".",
                        30,
                        "exercise timeout result"
                )
        );

        assertEquals(WorkspaceGateway.CommandStatus.TIMED_OUT, result.status());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(result.stdout().getBytes(StandardCharsets.UTF_8).length
                <= WorkspaceGateway.MAX_COMMAND_STREAM_BYTES);
        assertTrue(result.stderr().getBytes(StandardCharsets.UTF_8).length
                <= WorkspaceGateway.MAX_COMMAND_STREAM_BYTES);
    }

    @Test
    void shouldAdvanceGenerationWhenTimedOutCommandStillChangedWorkspace() throws Exception {
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) -> {
            Files.writeString(workingDirectory.resolve("timeout-output.txt"), "partial output\n");
            return new WorkspaceCommandExecutor.ProcessResult(
                    true,
                    null,
                    30_000,
                    "",
                    "timed out",
                    false,
                    false
            );
        };
        Fixture fixture = fixture(executor);

        WorkspaceGateway.CommandResult result = fixture.gateway().runCommand(
                fixture.workspaceId(),
                new WorkspaceGateway.CommandRequest(
                        0,
                        List.of("test-runner"),
                        ".",
                        30,
                        "exercise timeout side effects"
                )
        );

        assertEquals(WorkspaceGateway.CommandStatus.TIMED_OUT, result.status());
        assertEquals(0, result.generationBefore());
        assertEquals(1, result.generationAfter());
        assertTrue(result.stateChanged());
        assertEquals(
                "partial output\n",
                Files.readString(fixture.root().resolve("timeout-output.txt"))
        );
    }

    @Test
    void shouldHoldWorkspaceWriteLockForTheWholeCommand() throws Exception {
        CountDownLatch commandStarted = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        WorkspaceCommandExecutor executor = (workingDirectory, argv, timeout) -> {
            commandStarted.countDown();
            if (!releaseCommand.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release command");
            }
            return new WorkspaceCommandExecutor.ProcessResult(
                    false,
                    0,
                    100,
                    "",
                    "",
                    false,
                    false
            );
        };
        Fixture fixture = fixture(executor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceGateway.CommandResult> command = pool.submit(() ->
                    fixture.gateway().runCommand(
                            fixture.workspaceId(),
                            new WorkspaceGateway.CommandRequest(
                                    0,
                                    List.of("test-runner"),
                                    ".",
                                    30,
                                    "hold mutation lock"
                            )
                    )
            );
            assertTrue(commandStarted.await(1, TimeUnit.SECONDS));

            Future<PatchBatchResult> patch = pool.submit(() -> fixture.gateway().applyPatch(
                    fixture.workspaceId(),
                    new WorkspaceMutationCommand(
                            0,
                            List.of(PatchOperation.create(0, "blocked.txt", "later\n"))
                    )
            ));
            assertThrows(TimeoutException.class, () -> patch.get(100, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(fixture.root().resolve("blocked.txt")));

            releaseCommand.countDown();
            assertEquals(WorkspaceGateway.CommandStatus.COMPLETED, command.get().status());
            assertEquals(PatchBatchStatus.SUCCESS, patch.get().status());
            assertTrue(Files.exists(fixture.root().resolve("blocked.txt")));
        } finally {
            releaseCommand.countDown();
            pool.shutdownNow();
        }
    }

    private Fixture fixture(WorkspaceCommandExecutor executor) {
        try {
            FakeObjectStorage storage = new FakeObjectStorage();
            byte[] main = "class Main {\n    String value = \"needle\";\n}\n"
                    .getBytes(StandardCharsets.UTF_8);
            byte[] readme = "base readme\n".getBytes(StandardCharsets.UTF_8);
            String mainBlob = GitObjectHasher.sha1(main).value();
            String readmeBlob = GitObjectHasher.sha1(readme).value();
            storage.writeObject(REPO_KEY, mainBlob, main);
            storage.writeObject(REPO_KEY, readmeBlob, readme);
            writeCommit(
                    storage,
                    REPO_KEY,
                    BASE_SHA,
                    null,
                    "base",
                    Map.of(
                            "src/Main.java", mainBlob,
                            "README.md", readmeBlob
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
