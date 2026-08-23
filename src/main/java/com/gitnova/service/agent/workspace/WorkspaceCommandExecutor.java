package com.gitnova.service.agent.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Executes a command inside the Workspace isolation boundary.
 *
 * <p>A production implementation must target the Workspace container. It must not execute
 * model commands directly inside the GitNova service process.</p>
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
            String stderr
    ) {
    }
}
