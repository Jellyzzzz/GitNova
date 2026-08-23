package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectId;
import com.gitnova.storage.RepoKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkspaceGatewayTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyOrderedBatchAndAdvanceGenerationExactlyOnce() throws Exception {
        Fixture fixture = fixture(0);
        Files.writeString(fixture.root().resolve("update.txt"), "before\n");
        Files.writeString(fixture.root().resolve("delete.txt"), "remove\n");

        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                0,
                List.of(
                        PatchOperation.create(0, "nested/create.txt", "created\n"),
                        PatchOperation.update(
                                1,
                                "update.txt",
                                "@@ -1 +1 @@\n-before\n+after\n"
                        ),
                        PatchOperation.delete(2, "delete.txt")
                )
        );

        PatchBatchResult result = fixture.gateway().applyPatch(fixture.workspaceId(), command);

        assertEquals(PatchBatchStatus.SUCCESS, result.status());
        assertEquals(0, result.generationBefore());
        assertEquals(1, result.generationAfter());
        assertEquals(1, fixture.state().generation());
        assertEquals("created\n", Files.readString(fixture.root().resolve("nested/create.txt")));
        assertEquals("after\n", Files.readString(fixture.root().resolve("update.txt")));
        assertFalse(Files.exists(fixture.root().resolve("delete.txt")));
        assertTrue(result.operationResults().stream().allMatch(
                operation -> operation.status() == PatchOperationStatus.APPLIED
        ));
    }

    @Test
    void shouldRejectStaleGenerationWithoutChangingWorkspace() {
        Fixture fixture = fixture(2);
        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                1,
                List.of(PatchOperation.create(0, "stale.txt", "must not exist"))
        );

        PatchBatchResult result = fixture.gateway().applyPatch(fixture.workspaceId(), command);

        assertEquals(PatchBatchStatus.CONFLICT, result.status());
        assertEquals(2, result.generationBefore());
        assertEquals(2, result.generationAfter());
        assertEquals(2, fixture.state().generation());
        assertFalse(Files.exists(fixture.root().resolve("stale.txt")));
        assertEquals(PatchOperationStatus.NOT_ATTEMPTED, result.operationResults().get(0).status());
    }

    @Test
    void shouldExposePartialSuccessAndPreserveAppliedPrefix() throws Exception {
        Fixture fixture = fixture(0);
        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                0,
                List.of(
                        PatchOperation.create(0, "applied.txt", "kept\n"),
                        PatchOperation.update(
                                1,
                                "missing.txt",
                                "@@ -1 +1 @@\n-before\n+after\n"
                        ),
                        PatchOperation.create(2, "not-attempted.txt", "no\n")
                )
        );

        PatchBatchResult result = fixture.gateway().applyPatch(fixture.workspaceId(), command);

        assertEquals(PatchBatchStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(1, result.generationAfter());
        assertEquals(1, fixture.state().generation());
        assertEquals("kept\n", Files.readString(fixture.root().resolve("applied.txt")));
        assertFalse(Files.exists(fixture.root().resolve("not-attempted.txt")));
        assertEquals(
                List.of(
                        PatchOperationStatus.APPLIED,
                        PatchOperationStatus.FAILED,
                        PatchOperationStatus.NOT_ATTEMPTED
                ),
                result.operationResults().stream().map(PatchOperationResult::status).toList()
        );
    }

    @Test
    void shouldKeepGenerationWhenFirstOperationFails() {
        Fixture fixture = fixture(0);
        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                0,
                List.of(
                        PatchOperation.delete(0, "missing.txt"),
                        PatchOperation.create(1, "not-attempted.txt", "no\n")
                )
        );

        PatchBatchResult result = fixture.gateway().applyPatch(fixture.workspaceId(), command);

        assertEquals(PatchBatchStatus.FAILED, result.status());
        assertEquals(0, result.generationAfter());
        assertEquals(0, fixture.state().generation());
        assertFalse(Files.exists(fixture.root().resolve("not-attempted.txt")));
    }

    @Test
    void shouldWaitForWorkspaceReadersBeforeStartingMutation() throws Exception {
        Fixture fixture = fixture(0);
        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                0,
                List.of(PatchOperation.create(0, "after-read.txt", "done\n"))
        );
        Lock readLock = fixture.state().lock().readLock();
        readLock.lock();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch submitted = new CountDownLatch(1);
        try {
            Future<PatchBatchResult> future = executor.submit(() -> {
                submitted.countDown();
                return fixture.gateway().applyPatch(fixture.workspaceId(), command);
            });
            assertTrue(submitted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(fixture.root().resolve("after-read.txt")));

            readLock.unlock();
            PatchBatchResult result = future.get(2, TimeUnit.SECONDS);

            assertEquals(PatchBatchStatus.SUCCESS, result.status());
            assertTrue(Files.exists(fixture.root().resolve("after-read.txt")));
        } finally {
            if (fixture.state().lock().getReadHoldCount() > 0) {
                readLock.unlock();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectPathEscapeBeforeAnyMutation() {
        Fixture fixture = fixture(0);
        WorkspaceMutationCommand command = new WorkspaceMutationCommand(
                0,
                List.of(PatchOperation.create(0, "../escape.txt", "no"))
        );

        PatchBatchResult result = fixture.gateway().applyPatch(fixture.workspaceId(), command);

        assertEquals(PatchBatchStatus.FAILED, result.status());
        assertEquals("INVALID_WORKSPACE_PATH", result.operationResults().get(0).errorCode());
        assertEquals(0, fixture.state().generation());
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    private Fixture fixture(long generation) {
        Path root = tempDir.resolve("workspace-" + generation);
        try {
            Files.createDirectories(root);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }

        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkspaceHandle handle = new WorkspaceHandle(
                workspaceId,
                RepoKey.of(1, 10),
                new SnapshotScope(GitObjectId.of("a".repeat(40))),
                root,
                WorkspaceStatus.READY,
                generation
        );
        LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry();
        registry.register(handle);
        return new Fixture(
                workspaceId,
                root,
                registry.require(workspaceId),
                new LocalWorkspaceGateway(registry)
        );
    }

    private record Fixture(
            WorkspaceId workspaceId,
            Path root,
            LocalWorkspaceRegistry.LocalWorkspaceState state,
            LocalWorkspaceGateway gateway
    ) {
    }
}
