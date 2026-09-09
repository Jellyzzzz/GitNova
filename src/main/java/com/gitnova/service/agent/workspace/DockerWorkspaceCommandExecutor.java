package com.gitnova.service.agent.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs only Docker CLI on the host; repository argv is executed exclusively in a container. */
public final class DockerWorkspaceCommandExecutor implements WorkspaceCommandExecutor {
    static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);
    private final DockerWorkspaceProperties properties;
    private final DockerClient client;

    // One small seam for deterministic lifecycle/failure tests, not another service layer.
    @FunctionalInterface
    interface DockerClient {
        ProcessResult invoke(List<String> arguments, Duration timeout) throws Exception;
    }

    public DockerWorkspaceCommandExecutor(DockerWorkspaceProperties properties) {
        this(properties, DockerWorkspaceCommandExecutor::invokeDocker);
    }

    DockerWorkspaceCommandExecutor(DockerWorkspaceProperties properties, DockerClient client) {
        this.properties = Objects.requireNonNull(properties);
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public ProcessResult execute(Path workspaceRoot, Path workingDirectory,
                                 List<String> argv, Duration timeout) throws Exception {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path directory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.equals(root.toRealPath()) || !directory.startsWith(root)
                || !directory.equals(directory.toRealPath()) || !Files.isDirectory(directory)
                || root.toString().contains(",") || argv == null || argv.isEmpty()
                || argv.stream().anyMatch(arg -> arg == null || arg.indexOf('\0') >= 0)
                || argv.get(0).isBlank() || argv.get(0).startsWith("-")
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid trusted Workspace root, directory, command or timeout");
        }
        WorkspaceCommandExecutor.requireNoPendingCommand(root);
        String name = "gitnova-command-" + UUID.randomUUID();
        Path pending = WorkspaceCommandExecutor.pendingCommandFile(root);
        // Commit identity before contacting Docker. Even an ambiguous create/start must be reconciled.
        try (FileChannel file = FileChannel.open(pending, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(name + "\n");
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
        long started = System.nanoTime();
        boolean createRequestResolved = false;
        try {
            ProcessResult created = client.invoke(createArguments(root, directory, argv, timeout, name), CONTROL_TIMEOUT);
            createRequestResolved = !created.timedOut();
            requireSuccess(created, "Docker container creation failed");
            ProcessResult attached = client.invoke(command("start", "--attach", name), timeout);
            if (attached.timedOut()) {
                return new ProcessResult(true, null, elapsedMillis(started), attached.stdout(), attached.stderr(),
                        attached.stdoutTruncated(), attached.stderrTruncated());
            }
            // CLI exit status is not the repository process exit status.
            ProcessResult inspected = client.invoke(command("inspect", "--format",
                    "{{.State.Status}} {{.State.Running}} {{.State.ExitCode}}", name), CONTROL_TIMEOUT);
            requireSuccess(inspected, "Docker completion inspection failed");
            String[] state = inspected.stdout().strip().split(" ");
            if (state.length != 3 || !"exited".equals(state[0]) || !"false".equals(state[1])) {
                throw new IOException("Docker command is not confirmed stopped");
            }
            return new ProcessResult(false, Integer.parseInt(state[2]), elapsedMillis(started),
                    attached.stdout(), attached.stderr(), attached.stdoutTruncated(), attached.stderrTruncated());
        } finally {
            // A failed cleanup MUST leave the guard. Never announce safe completion on daemon failure.
            boolean interrupted = Thread.interrupted();
            try {
                removeAndConfirmAbsent(name);
                // A timed-out/ambiguous create request could still complete at the daemon later.
                // A single empty listing cannot prove that request will never create a container.
                if (createRequestResolved) Files.delete(pending);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    List<String> createArguments(Path root, Path directory, List<String> argv, Duration timeout, String name) {
        List<String> args = command("create", "--name", name, "--pull", "never",
                "--network", "none", "--user", properties.user(), "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges=true",
                "--cpus", Double.toString(properties.cpus()),
                "--memory", properties.memoryMb() + "m", "--memory-swap", properties.memoryMb() + "m",
                "--pids-limit", Integer.toString(properties.pidsLimit()),
                "--log-driver", "none", "--stop-timeout", "1",
                "--mount", "type=bind,src=" + root + ",dst=/workspace",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
                "--env", "HOME=/tmp",
                // Docker client proxy defaults may contain credentials; override automatic injection.
                "--env", "HTTP_PROXY=", "--env", "HTTPS_PROXY=", "--env", "ALL_PROXY=", "--env", "NO_PROXY=",
                "--env", "http_proxy=", "--env", "https_proxy=", "--env", "all_proxy=", "--env", "no_proxy=",
                "--workdir", "/workspace/" + root.relativize(directory),
                // Backstop survives JVM/client death. PID 1 exit destroys remaining container processes.
                "--entrypoint", "/usr/bin/timeout", properties.image(),
                "--signal=KILL", (timeout.toSeconds() + 5) + "s");
        args.addAll(argv);
        return args;
    }

    private void removeAndConfirmAbsent(String name) throws Exception {
        client.invoke(command("rm", "--force", name), CONTROL_TIMEOUT);
        ProcessResult remaining = client.invoke(command("ps", "--all", "--quiet", "--no-trunc",
                "--filter", "name=^/" + name + "$"), CONTROL_TIMEOUT);
        requireSuccess(remaining, "Cannot verify Docker cleanup; Workspace remains blocked");
        if (!remaining.stdout().isBlank() || remaining.stdoutTruncated()) {
            throw new IOException("Docker container remains; Workspace remains blocked");
        }
    }

    private List<String> command(String... arguments) {
        List<String> result = new ArrayList<>();
        result.add(properties.executable());
        result.addAll(List.of(arguments));
        return result;
    }

    private static void requireSuccess(ProcessResult result, String message) throws IOException {
        if (result.timedOut() || !Integer.valueOf(0).equals(result.exitCode())) {
            throw new IOException(message); // Do not leak daemon paths/config or credentials into observations.
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    static ProcessResult invokeDocker(List<String> arguments, Duration timeout) throws Exception {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(arguments).start();
        process.getOutputStream().close();
        var readers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "docker-output-drain");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var stdout = readers.submit(() -> drain(process.getInputStream()));
            var stderr = readers.submit(() -> drain(process.getErrorStream()));
            try {
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Docker CLI did not stop");
                }
                Captured out = stdout.get(2, TimeUnit.SECONDS);
                Captured err = stderr.get(2, TimeUnit.SECONDS);
                return new ProcessResult(!finished, finished ? process.exitValue() : null,
                        elapsedMillis(started), out.text(), err.text(), out.truncated(), err.truncated());
            } finally {
                process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
                stdout.cancel(true);
                stderr.cancel(true);
            }
        } finally {
            readers.shutdownNow();
        }
    }

    private static Captured drain(InputStream stream) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int retain = Math.min(read, MAX_OUTPUT_BYTES - kept.size());
            kept.write(buffer, 0, retain);
            truncated |= retain != read;
        }
        return new Captured(kept.toString(StandardCharsets.UTF_8), truncated);
    }

    private record Captured(String text, boolean truncated) { }
}
