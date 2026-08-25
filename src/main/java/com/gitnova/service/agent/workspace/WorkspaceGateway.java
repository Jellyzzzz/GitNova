package com.gitnova.service.agent.workspace;

import java.util.List;
import java.util.Objects;

/** Provider-neutral access to one trusted, session-owned Workspace. */
public interface WorkspaceGateway {

    int MAX_PATH_CHARS = 4096;
    int MAX_GLOB_CHARS = 1024;
    int MAX_QUERY_CHARS = 4096;
    int MAX_LIST_ENTRIES = 1000;
    int MAX_FIND_RESULTS = 1000;
    int MAX_SEARCH_RESULTS = 500;
    int MAX_SEARCH_FILE_BYTES = 1024 * 1024;
    int MAX_READ_FILE_BYTES = 1024 * 1024;
    int MAX_READ_OUTPUT_BYTES = 24 * 1024;
    int MAX_DIFF_FILES = 1000;
    int MAX_DIFF_FILE_BYTES = 4 * 1024 * 1024;
    long MAX_DIFF_TOTAL_BYTES = 64L * 1024 * 1024;
    int MAX_DIFF_TEXT_BYTES = 1024 * 1024;
    int MAX_WORKSPACE_FILES = 20_000;
    long MAX_FINGERPRINT_BYTES = 256L * 1024 * 1024;
    int MAX_COMMAND_ARG_COUNT = 128;
    int MAX_COMMAND_ARG_BYTES = 8 * 1024;
    int MAX_COMMAND_TOTAL_ARG_BYTES = 64 * 1024;
    int MAX_COMMAND_TIMEOUT_SECONDS = 600;
    int MAX_COMMAND_PURPOSE_CHARS = 1024;
    int MAX_COMMAND_STREAM_BYTES = 8 * 1024;

    PatchBatchResult applyPatch(
            WorkspaceId workspaceId,
            WorkspaceMutationCommand command
    );

    /**
     * Reconciles the in-memory Workspace generation with repository-visible files on disk.
     * Runtime calls this before model requests and before every tool dispatch, never while a
     * model request is in flight.
     */
    default WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
        throw new UnsupportedOperationException("refreshWorkspace is not supported");
    }

    default FileListing listFiles(WorkspaceId workspaceId, String directory) {
        throw new UnsupportedOperationException("listFiles is not supported");
    }

    default FileSearch findFiles(WorkspaceId workspaceId, String glob) {
        throw new UnsupportedOperationException("findFiles is not supported");
    }

    default TextSearch searchText(
            WorkspaceId workspaceId,
            String query,
            boolean caseSensitive
    ) {
        throw new UnsupportedOperationException("searchText is not supported");
    }

    default FileContent readFile(
            WorkspaceId workspaceId,
            String filePath,
            int startLine,
            int endLine
    ) {
        throw new UnsupportedOperationException("readFile is not supported");
    }

    default WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
        throw new UnsupportedOperationException("getWorkspaceDiff is not supported");
    }

    default CommandResult runCommand(
            WorkspaceId workspaceId,
            CommandRequest request
    ) {
        throw new UnsupportedOperationException("runCommand is not supported");
    }

    enum FileType {
        FILE,
        DIRECTORY
    }

    record WorkspaceRefresh(
            long generationBefore,
            long generationAfter,
            boolean changed
    ) {
        public WorkspaceRefresh {
            if (generationBefore < 0
                    || generationAfter < generationBefore
                    || generationAfter > generationBefore + 1
                    || changed != (generationAfter > generationBefore)) {
                throw new IllegalArgumentException("invalid Workspace refresh result");
            }
        }
    }

    record FileEntry(String path, FileType type, long size) {
        public FileEntry {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(type, "type must not be null");
            if (path.isBlank() || size < 0) {
                throw new IllegalArgumentException("invalid file entry");
            }
        }
    }

    record FileListing(
            long generation,
            String directory,
            List<FileEntry> entries,
            boolean truncated
    ) {
        public FileListing {
            Objects.requireNonNull(directory, "directory must not be null");
            entries = List.copyOf(entries);
        }
    }

    record FileSearch(
            long generation,
            String glob,
            List<String> paths,
            boolean truncated
    ) {
        public FileSearch {
            Objects.requireNonNull(glob, "glob must not be null");
            paths = List.copyOf(paths);
        }
    }

    record TextMatch(String filePath, int lineNumber, String preview) {
        public TextMatch {
            Objects.requireNonNull(filePath, "filePath must not be null");
            Objects.requireNonNull(preview, "preview must not be null");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be positive");
            }
        }
    }

    record TextSearch(
            long generation,
            String query,
            boolean caseSensitive,
            List<TextMatch> matches,
            boolean truncated
    ) {
        public TextSearch {
            Objects.requireNonNull(query, "query must not be null");
            matches = List.copyOf(matches);
        }
    }

    record FileLine(int lineNumber, String content) {
        public FileLine {
            Objects.requireNonNull(content, "content must not be null");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be positive");
            }
        }
    }

    record FileContent(
            long generation,
            String filePath,
            int startLine,
            int endLine,
            int totalLines,
            List<FileLine> lines
    ) {
        public FileContent {
            Objects.requireNonNull(filePath, "filePath must not be null");
            lines = List.copyOf(lines);
        }
    }

    enum DiffChangeType {
        ADDED,
        MODIFIED,
        DELETED
    }

    record DiffFile(
            String filePath,
            DiffChangeType changeType,
            int addedLines,
            int deletedLines,
            int hunks,
            boolean binary
    ) {
        public DiffFile {
            Objects.requireNonNull(filePath, "filePath must not be null");
            Objects.requireNonNull(changeType, "changeType must not be null");
        }
    }

    record WorkspaceDiff(
            long generation,
            List<DiffFile> files,
            int totalAddedLines,
            int totalDeletedLines,
            int totalHunks,
            boolean containsBinary,
            String unifiedDiff
    ) {
        public WorkspaceDiff {
            files = List.copyOf(files);
            Objects.requireNonNull(unifiedDiff, "unifiedDiff must not be null");
        }
    }

    record CommandRequest(
            long expectedGeneration,
            List<String> argv,
            String workingDirectory,
            int timeoutSeconds,
            String purpose
    ) {
        public CommandRequest {
            if (expectedGeneration < 0) {
                throw new IllegalArgumentException("expectedGeneration must not be negative");
            }
            argv = List.copyOf(argv);
            if (argv.isEmpty()
                    || argv.size() > MAX_COMMAND_ARG_COUNT
                    || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("argv must contain non-blank arguments");
            }
            long totalArgumentBytes = 0;
            for (String argument : argv) {
                int argumentBytes = argument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (argumentBytes > MAX_COMMAND_ARG_BYTES) {
                    throw new IllegalArgumentException("command argument exceeds the size limit");
                }
                totalArgumentBytes += argumentBytes;
            }
            if (totalArgumentBytes > MAX_COMMAND_TOTAL_ARG_BYTES) {
                throw new IllegalArgumentException("command arguments exceed the total size limit");
            }
            Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
            Objects.requireNonNull(purpose, "purpose must not be null");
            if (workingDirectory.length() > MAX_PATH_CHARS
                    || timeoutSeconds < 1
                    || timeoutSeconds > MAX_COMMAND_TIMEOUT_SECONDS
                    || purpose.isBlank()
                    || purpose.length() > MAX_COMMAND_PURPOSE_CHARS) {
                throw new IllegalArgumentException("invalid command request");
            }
        }
    }

    enum CommandStatus {
        COMPLETED,
        TIMED_OUT,
        CONFLICT,
        EXECUTION_FAILED
    }

    record CommandResult(
            CommandStatus status,
            long expectedGeneration,
            long generationBefore,
            long generationAfter,
            Integer exitCode,
            long durationMillis,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            String errorCode,
            String message
    ) {
        public CommandResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(stdout, "stdout must not be null");
            Objects.requireNonNull(stderr, "stderr must not be null");
            if (generationBefore < 0
                    || generationAfter < generationBefore
                    || durationMillis < 0) {
                throw new IllegalArgumentException("invalid command result counters");
            }
        }

        public boolean stateChanged() {
            return generationAfter > generationBefore;
        }
    }
}
