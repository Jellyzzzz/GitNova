package com.gitnova.service.agent.workspace;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    /** Outside the mounted tree: repository commands cannot remove this recovery guard. */
    static Path pendingCommandFile(Path workspaceRoot) {
        return workspaceRoot.resolveSibling("." + workspaceRoot.getFileName() + ".pending-command");
    }

    static void requireNoPendingCommand(Path workspaceRoot) {
        if (!Files.notExists(pendingCommandFile(workspaceRoot), LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceOperationException(WorkspaceOperationException.Reason.WORKSPACE_UNAVAILABLE,
                    "WORKSPACE_COMMAND_UNRECONCILED",
                    "A previous command has not been confirmed stopped; reconcile its container before continuing");
        }
    }

    ProcessResult execute(
            Path workspaceRoot,
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
