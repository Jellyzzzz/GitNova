package com.gitnova.service.agent.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Executes a command inside the Workspace isolation boundary.
 *
 * <p>A production implementation must target the Workspace container. It must not execute
 * model commands directly inside the GitNova service process. It owns the isolation guarantees
 * that the Gateway cannot provide: non-root execution, network disabled by default, an explicit
 * environment allowlist with no service secrets, no Docker socket or host storage mounts, CPU /
 * memory / PID limits, and termination of the complete container or process cgroup on timeout.
 * stdout and stderr must be drained concurrently into bounded collectors; the Gateway applies a
 * second defensive truncation before exposing the result to the model.</p>
 */
@FunctionalInterface
public interface WorkspaceCommandExecutor {

    ProcessResult execute(
            Path workingDirectory,
            List<String> argv,
            Duration timeout
    ) throws Exception;

    record ProcessResult(
            boolean timedOut,
            Integer exitCode,
            long durationMillis,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated
    ) {
    }
}
