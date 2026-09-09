package com.gitnova.service.agent.workspace;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Requires a running local Docker engine and the prebuilt workspace image; no fake executor. */
@Tag("docker-it")
class DockerWorkspaceIntegrationTest {
    private Path temporary;

    @BeforeEach
    void createSharedFixture() throws Exception {
        // Colima shares the project, not macOS's private /var/folders JUnit temporary directory.
        Path base = Files.createDirectories(Path.of("target", "docker-it"));
        temporary = Files.createTempDirectory(base, "workspace-").toRealPath();
    }

    @AfterEach
    void deleteConfirmedStoppedFixture() throws Exception {
        if (temporary != null && Files.notExists(WorkspaceCommandExecutor.pendingCommandFile(temporary))) {
            FileSystemUtils.deleteRecursively(temporary);
        }
    }

    private DockerWorkspaceCommandExecutor executor() {
        return new DockerWorkspaceCommandExecutor(new DockerWorkspaceProperties(true,
                System.getenv().getOrDefault("WORKSPACE_DOCKER_EXECUTABLE", "docker"),
                System.getenv().getOrDefault("WORKSPACE_DOCKER_IMAGE", "gitnova-workspace:java21"),
                System.getenv().getOrDefault("WORKSPACE_DOCKER_USER", "1000:1000"), 1, 512, 128), (args, timeout) -> {
            var result = DockerWorkspaceCommandExecutor.invokeDocker(args, timeout);
            if (args.get(1).equals("create") && !Integer.valueOf(0).equals(result.exitCode())) {
                System.err.println("Synthetic Docker fixture creation failed: " + result.stderr());
            }
            return result;
        });
    }

    @Test
    void realContainerCompilesJavaWritesWorkspaceButCannotAccessHostSecretsOrNetwork() throws Exception {
        Path root = temporary.toRealPath();
        Path src = Files.createDirectory(root.resolve("src"));
        Files.writeString(src.resolve("Smoke.java"), "class Smoke { public static void main(String[] a) { System.out.println(6 * 7); } }");
        var result = executor().execute(root, src, List.of("sh", "-ec", """
                test "$(id -u)" != 0
                test ! -S /var/run/docker.sock
                test -z "${DEEPSEEK_API_KEY:-}${DB_PASSWORD:-}${OPENAI_API_KEY:-}"
                test -z "${HTTP_PROXY:-}${http_proxy:-}${HTTPS_PROXY:-}${https_proxy:-}"
                test ! -e /Users/zhaoguodong
                test "$(ls /sys/class/net)" = lo
                test "$(cat /sys/fs/cgroup/pids.max)" = 128
                test "$(cat /sys/fs/cgroup/memory.max)" = 536870912
                if touch /root/escape 2>/dev/null; then exit 91; fi
                javac -d /tmp Smoke.java
                java -cp /tmp Smoke > ../answer.txt
                cat ../answer.txt
                """), Duration.ofSeconds(20));
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("42\n", Files.readString(root.resolve("answer.txt")));
        assertFalse(Files.exists(src.resolve("Smoke.class"))); // Build artifacts stayed in tmpfs.
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void timeoutKillsBackgroundDescendantsAndPreservesAlreadyWrittenFiles() throws Exception {
        Path root = temporary.toRealPath();
        var result = executor().execute(root, root, List.of("sh", "-c",
                "echo before > before.txt; (sleep 4; echo escaped > escaped.txt) & wait"), Duration.ofSeconds(1));
        assertTrue(result.timedOut());
        Thread.sleep(4500);
        assertTrue(Files.exists(root.resolve("before.txt")));
        assertFalse(Files.exists(root.resolve("escaped.txt")));
        assertFalse(Files.exists(WorkspaceCommandExecutor.pendingCommandFile(root)));
    }

    @Test
    void boundsRealContainerOutputAndReturnsNonZeroCommandExitWithoutInfrastructureFailure() throws Exception {
        Path root = temporary.toRealPath();
        var result = executor().execute(root, root, List.of("sh", "-c",
                "head -c 100000 /dev/zero; head -c 100000 /dev/zero >&2; exit 9"), Duration.ofSeconds(10));
        assertEquals(9, result.exitCode());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertEquals(65536, result.stdout().length());
        assertEquals(65536, result.stderr().length());
    }

    @Test
    void realCommandSideEffectAdvancesGatewayGenerationAndRejectsStaleNextWrite() {
        WorkspaceId id = WorkspaceId.generate();
        LocalWorkspaceRegistry registry = new LocalWorkspaceRegistry();
        registry.register(new WorkspaceHandle(id, com.gitnova.storage.RepoKey.of(1L, 1L),
                SnapshotScope.of("a".repeat(40)), temporary, WorkspaceStatus.READY, 0));
        LocalWorkspaceGateway gateway = new LocalWorkspaceGateway(registry, null, executor());
        WorkspaceExecutionPermit permit = new WorkspaceExecutionPermit("docker-run", id, 1);
        var request = new WorkspaceGateway.CommandRequest(0,
                List.of("sh", "-c", "echo generated > generated.txt"), ".", 10, "verify command side effect");
        var first = gateway.runCommand(id, permit, request);
        assertEquals(WorkspaceGateway.CommandStatus.COMPLETED, first.status(), first.message());
        assertEquals(0, first.exitCode());
        assertEquals(0, first.generationBefore());
        assertEquals(1, first.generationAfter());
        assertFalse(gateway.refreshWorkspace(id).changed());
        assertEquals("STALE_WORKSPACE_GENERATION", gateway.runCommand(id, permit, request).errorCode());
    }
}
