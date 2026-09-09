package com.gitnova.service.agent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerWorkspaceCommandExecutorTest {
    @TempDir Path temporary;
    private final DockerWorkspaceProperties policy = new DockerWorkspaceProperties(
            true, "docker", "gitnova-workspace:java21", "1000:1000", 1, 512, 128);

    @AfterEach
    void removeSyntheticRecoveryGuard() throws Exception {
        // These unit tests never contact a daemon, so no real side effect needs reconciliation.
        Files.deleteIfExists(WorkspaceCommandExecutor.pendingCommandFile(temporary.toRealPath()));
    }

    @Test
    void mountsOnlyRootAndKeepsNestedWorkingDirectoryWithFixedIsolationPolicy() throws Exception {
        Path root = temporary.toRealPath();
        Path nested = Files.createDirectory(root.resolve("src"));
        List<List<String>> commands = new ArrayList<>();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> {
            commands.add(args);
            assertTrue(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
            return result(args.get(1).equals("inspect") ? "exited false 7" : "", 0);
        });
        var result = executor.execute(root, nested, List.of("sh", "-c", "exit 7"), Duration.ofSeconds(2));
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
        var create = commands.get(0);
        assertTrue(create.containsAll(List.of("--network", "none", "--read-only", "--cap-drop", "ALL",
                "no-new-privileges=true", "1000:1000", "--pids-limit", "--memory-swap", "--log-driver")));
        assertEquals(1, create.stream().filter("--mount"::equals).count());
        assertTrue(create.contains("type=bind,src=" + root + ",dst=/workspace"));
        assertTrue(create.contains("/workspace/src"));
        assertEquals(List.of("sh", "-c", "exit 7"), create.subList(create.size() - 3, create.size()));
        assertEquals(List.of("create", "start", "inspect", "rm", "ps"), commands.stream().map(a -> a.get(1)).toList());
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void timeoutRemovesWholeContainerBeforeReturning() throws Exception {
        Path root = temporary.toRealPath();
        List<String> stages = new ArrayList<>();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> {
            stages.add(args.get(1));
            return args.get(1).equals("start")
                    ? new WorkspaceCommandExecutor.ProcessResult(true, null, 2000, "partial", "", false, false)
                    : result("", 0);
        });
        var result = executor.execute(root, root, List.of("sleep", "20"), Duration.ofSeconds(1));
        assertTrue(result.timedOut());
        assertEquals("partial", result.stdout());
        assertEquals(List.of("create", "start", "rm", "ps"), stages);
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void ambiguousDaemonFailureLeavesRecoveryGuardAndBlocksNextCommand() throws Exception {
        Path root = temporary.toRealPath();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> {
            throw new IOException("daemon unavailable");
        });
        assertThrows(IOException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
        assertTrue(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
        assertThrows(WorkspaceOperationException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
    }

    @Test
    void survivingContainerCannotBeReportedAsSuccessful() throws Exception {
        Path root = temporary.toRealPath();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> result(
                args.get(1).equals("inspect") ? "exited false 0" : args.get(1).equals("ps") ? "still-present" : "", 0));
        assertThrows(IOException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
        assertThrows(WorkspaceOperationException.class, () -> WorkspaceCommandExecutor.requireNoPendingCommand(root));
    }

    @Test
    void failedCreateMayReleaseGuardOnlyAfterConfirmedAbsence() throws Exception {
        Path root = temporary.toRealPath();
        var executor = new DockerWorkspaceCommandExecutor(policy,
                (args, timeout) -> result("", args.get(1).equals("create") ? 125 : 0));
        assertThrows(IOException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void ambiguousCreateKeepsGuardEvenIfContainerListingIsCurrentlyEmpty() throws Exception {
        Path root = temporary.toRealPath();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> args.get(1).equals("create")
                ? new WorkspaceCommandExecutor.ProcessResult(true, null, 15000, "", "", false, false)
                : result("", 0));
        assertThrows(IOException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
        assertTrue(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void failedStartOfCreatedContainerCannotMasqueradeAsExitZero() throws Exception {
        Path root = temporary.toRealPath();
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> result(
                args.get(1).equals("inspect") ? "created false 0" : "", args.get(1).equals("start") ? 1 : 0));
        assertThrows(IOException.class, () -> executor.execute(root, root, List.of("true"), Duration.ofSeconds(1)));
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void rejectsEscapingOrSymlinkDirectoryBeforeContactingDocker() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        Path outside = Files.createDirectory(temporary.resolve("outside")).toRealPath();
        Path link = Files.createSymbolicLink(root.resolve("link"), outside);
        var executor = new DockerWorkspaceCommandExecutor(policy, (args, timeout) -> {
            fail("Docker must not be called"); return null;
        });
        assertThrows(IllegalArgumentException.class, () -> executor.execute(root, outside, List.of("true"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(root, link, List.of("true"), Duration.ofSeconds(1)));
    }

    @Test
    void concurrentlyDrainsAndCapsBothStreamsWithoutDeadlocking() throws Exception {
        // Fixed synthetic host process tests the transport only, never model-provided argv.
        var result = DockerWorkspaceCommandExecutor.invokeDocker(List.of("/bin/sh", "-c",
                "head -c 100000 /dev/zero; head -c 100000 /dev/zero >&2"), Duration.ofSeconds(5));
        assertEquals(0, result.exitCode());
        assertEquals(65536, result.stdout().length());
        assertEquals(65536, result.stderr().length());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void rejectsRootUserAndInvalidResourceConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new DockerWorkspaceProperties(
                true, "docker", "image", "0:0", 1, 512, 128));
    }

    private WorkspaceCommandExecutor.ProcessResult result(String output, int code) {
        return new WorkspaceCommandExecutor.ProcessResult(false, code, 1, output, "", false, false);
    }
}
