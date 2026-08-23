package com.gitnova.service.agent.workspace;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.concurrent.locks.Lock;

public final class LocalWorkspaceGateway implements WorkspaceGateway {

    private final LocalWorkspaceRegistry registry;
    private final GitObjectReader gitObjectReader;
    private final WorkspaceCommandExecutor commandExecutor;

    public LocalWorkspaceGateway(LocalWorkspaceRegistry registry) {
        this(registry, null, null);
    }

    public LocalWorkspaceGateway(
            LocalWorkspaceRegistry registry,
            GitObjectReader gitObjectReader,
            WorkspaceCommandExecutor commandExecutor
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.gitObjectReader = gitObjectReader;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public WorkspaceGateway.FileListing listFiles(
            WorkspaceId workspaceId,
            String directory
    ) {
        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            Path resolved = resolveReadablePath(state.root(), directory, true);
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.NOT_FOUND,
                        "DIRECTORY_NOT_FOUND",
                        "Workspace directory does not exist"
                );
            }
            try (var entries = Files.list(resolved)) {
                List<WorkspaceGateway.FileEntry> result = entries
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> !isInternalPath(state.root(), path))
                        .sorted(Comparator.comparing(path -> repositoryPath(state.root(), path)))
                        .map(path -> toFileEntry(state.root(), path))
                        .toList();
                return new WorkspaceGateway.FileListing(
                        state.generation(),
                        normalizeDirectoryLabel(directory),
                        result
                );
            } catch (IOException exception) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                        "WORKSPACE_LIST_FAILED",
                        "Could not list Workspace directory",
                        exception
                );
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public WorkspaceGateway.FileSearch findFiles(
            WorkspaceId workspaceId,
            String glob
    ) {
        Objects.requireNonNull(glob, "glob must not be null");
        if (glob.isBlank()) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.INVALID_PATH,
                    "INVALID_FILE_GLOB",
                    "File glob must not be blank"
            );
        }

        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            PathMatcher matcher;
            try {
                matcher = state.root().getFileSystem().getPathMatcher("glob:" + glob);
            } catch (IllegalArgumentException exception) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.INVALID_PATH,
                        "INVALID_FILE_GLOB",
                        "File glob is invalid",
                        exception
                );
            }

            try (var paths = Files.walk(state.root())) {
                List<String> matches = paths
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> !isInternalPath(state.root(), path))
                        .map(state.root()::relativize)
                        .filter(matcher::matches)
                        .map(LocalWorkspaceGateway::portablePath)
                        .sorted()
                        .toList();
                return new WorkspaceGateway.FileSearch(
                        state.generation(),
                        glob,
                        matches
                );
            } catch (IOException exception) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                        "WORKSPACE_SEARCH_FAILED",
                        "Could not search Workspace files",
                        exception
                );
            }
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public WorkspaceGateway.TextSearch searchText(
            WorkspaceId workspaceId,
            String query,
            boolean caseSensitive
    ) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isEmpty()) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                    "EMPTY_SEARCH_QUERY",
                    "Search query must not be empty"
            );
        }

        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            String expected = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
            List<WorkspaceGateway.TextMatch> matches = new ArrayList<>();

            try (var paths = Files.walk(state.root())) {
                List<Path> files = paths
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> !isInternalPath(state.root(), path))
                        .sorted(Comparator.comparing(path -> repositoryPath(state.root(), path)))
                        .toList();

                for (Path file : files) {
                    byte[] bytes = Files.readAllBytes(file);
                    String text = tryDecodeUtf8Text(bytes);
                    if (text == null) {
                        continue;
                    }
                    List<String> lines = text.lines().toList();
                    for (int index = 0; index < lines.size(); index++) {
                        String line = lines.get(index);
                        String candidate = caseSensitive
                                ? line
                                : line.toLowerCase(Locale.ROOT);
                        if (candidate.contains(expected)) {
                            matches.add(new WorkspaceGateway.TextMatch(
                                    repositoryPath(state.root(), file),
                                    index + 1,
                                    line
                            ));
                        }
                    }
                }
            } catch (IOException exception) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                        "WORKSPACE_TEXT_SEARCH_FAILED",
                        "Could not search Workspace text",
                        exception
                );
            }

            return new WorkspaceGateway.TextSearch(
                    state.generation(),
                    query,
                    caseSensitive,
                    matches
            );
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public WorkspaceGateway.FileContent readFile(
            WorkspaceId workspaceId,
            String filePath,
            int startLine,
            int endLine
    ) {
        if (startLine < 1 || endLine < startLine) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                    "INVALID_LINE_RANGE",
                    "Line range is invalid"
            );
        }

        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            Path target = resolveReadablePath(state.root(), filePath, false);
            byte[] bytes = readWorkspaceFile(state.root(), target);
            String text = tryDecodeUtf8Text(bytes);
            if (text == null) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                        "UNSUPPORTED_FILE_CONTENT",
                        "Workspace file must be valid UTF-8 text"
                );
            }

            List<String> allLines = text.lines().toList();
            if (!allLines.isEmpty() && startLine > allLines.size()) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                        "LINE_RANGE_OUT_OF_BOUNDS",
                        "startLine exceeds the number of lines in the file"
                );
            }
            int actualEnd = Math.min(endLine, allLines.size());
            List<WorkspaceGateway.FileLine> lines = new ArrayList<>();
            for (int lineNumber = startLine; lineNumber <= actualEnd; lineNumber++) {
                lines.add(new WorkspaceGateway.FileLine(
                        lineNumber,
                        allLines.get(lineNumber - 1)
                ));
            }
            return new WorkspaceGateway.FileContent(
                    state.generation(),
                    filePath,
                    startLine,
                    actualEnd,
                    allLines.size(),
                    lines
            );
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public WorkspaceGateway.WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
        if (gitObjectReader == null) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.SNAPSHOT_UNAVAILABLE,
                    "SNAPSHOT_READER_UNAVAILABLE",
                    "Workspace snapshot reader is not configured"
            );
        }

        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock readLock = state.lock().readLock();
        readLock.lock();
        try {
            CommitObject baseCommit;
            try {
                baseCommit = gitObjectReader.requireCommit(
                        state.repoKey().value(),
                        state.source().baseSha1().value()
                );
            } catch (GitObjectReadException exception) {
                throw mapSnapshotFailure(exception);
            }

            Map<String, byte[]> currentFiles = readWorkspaceTree(state.root());
            Set<String> paths = new TreeSet<>();
            paths.addAll(baseCommit.mapping().keySet());
            paths.addAll(currentFiles.keySet());

            List<WorkspaceGateway.DiffFile> changed = new ArrayList<>();
            StringBuilder unified = new StringBuilder();
            int totalAdded = 0;
            int totalDeleted = 0;
            int totalHunks = 0;
            boolean containsBinary = false;

            for (String path : paths) {
                byte[] before = readBaseBlob(state, baseCommit.mapping().get(path));
                byte[] after = currentFiles.get(path);
                if (java.util.Arrays.equals(before, after)) {
                    continue;
                }

                WorkspaceGateway.DiffChangeType changeType = before == null
                        ? WorkspaceGateway.DiffChangeType.ADDED
                        : after == null
                        ? WorkspaceGateway.DiffChangeType.DELETED
                        : WorkspaceGateway.DiffChangeType.MODIFIED;
                String beforeText = before == null ? "" : tryDecodeUtf8Text(before);
                String afterText = after == null ? "" : tryDecodeUtf8Text(after);
                boolean binary = beforeText == null || afterText == null;
                int added = 0;
                int deleted = 0;
                int hunks = 0;

                if (!binary) {
                    List<String> beforeLines = beforeText.lines().toList();
                    List<String> afterLines = afterText.lines().toList();
                    Patch<String> patch = DiffUtils.diff(beforeLines, afterLines);
                    hunks = patch.getDeltas().size();
                    for (var delta : patch.getDeltas()) {
                        deleted += delta.getSource().size();
                        added += delta.getTarget().size();
                    }
                    List<String> diffLines = UnifiedDiffUtils.generateUnifiedDiff(
                            "a/" + path,
                            "b/" + path,
                            beforeLines,
                            patch,
                            3
                    );
                    if (!diffLines.isEmpty()) {
                        if (unified.length() > 0) {
                            unified.append('\n');
                        }
                        unified.append(String.join("\n", diffLines)).append('\n');
                    }
                } else {
                    containsBinary = true;
                }

                totalAdded += added;
                totalDeleted += deleted;
                totalHunks += hunks;
                changed.add(new WorkspaceGateway.DiffFile(
                        path,
                        changeType,
                        added,
                        deleted,
                        hunks,
                        binary
                ));
            }

            return new WorkspaceGateway.WorkspaceDiff(
                    state.generation(),
                    changed,
                    totalAdded,
                    totalDeleted,
                    totalHunks,
                    containsBinary,
                    unified.toString()
            );
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public WorkspaceGateway.CommandResult runCommand(
            WorkspaceId workspaceId,
            WorkspaceGateway.CommandRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        LocalWorkspaceRegistry.LocalWorkspaceState state = registry.require(workspaceId);
        Lock writeLock = state.lock().writeLock();
        writeLock.lock();
        try {
            long generationBefore = state.generation();
            if (request.expectedGeneration() != generationBefore) {
                return new WorkspaceGateway.CommandResult(
                        WorkspaceGateway.CommandStatus.CONFLICT,
                        request.expectedGeneration(),
                        generationBefore,
                        generationBefore,
                        null,
                        0,
                        "",
                        "",
                        "STALE_WORKSPACE_GENERATION",
                        "Expected generation does not match the current Workspace"
                );
            }
            if (commandExecutor == null) {
                return new WorkspaceGateway.CommandResult(
                        WorkspaceGateway.CommandStatus.EXECUTION_FAILED,
                        request.expectedGeneration(),
                        generationBefore,
                        generationBefore,
                        null,
                        0,
                        "",
                        "",
                        "COMMAND_EXECUTOR_UNAVAILABLE",
                        "No isolated Workspace command executor is configured"
                );
            }

            Path workingDirectory = resolveReadablePath(
                    state.root(),
                    request.workingDirectory(),
                    true
            );
            if (!Files.isDirectory(workingDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw workspaceFailure(
                        WorkspaceOperationException.Reason.NOT_FOUND,
                        "WORKING_DIRECTORY_NOT_FOUND",
                        "Command working directory does not exist"
                );
            }

            String fingerprintBefore = workspaceFingerprint(state.root());
            WorkspaceCommandExecutor.ProcessResult execution;
            try {
                execution = commandExecutor.execute(
                        workingDirectory,
                        request.argv(),
                        Duration.ofSeconds(request.timeoutSeconds())
                );
            } catch (Exception exception) {
                String fingerprintAfter = workspaceFingerprint(state.root());
                if (!fingerprintBefore.equals(fingerprintAfter)) {
                    state.advanceGeneration();
                }
                return new WorkspaceGateway.CommandResult(
                        WorkspaceGateway.CommandStatus.EXECUTION_FAILED,
                        request.expectedGeneration(),
                        generationBefore,
                        state.generation(),
                        null,
                        0,
                        "",
                        "",
                        "COMMAND_EXECUTION_FAILED",
                        "Workspace command could not be executed"
                );
            }

            String fingerprintAfter = workspaceFingerprint(state.root());
            if (!fingerprintBefore.equals(fingerprintAfter)) {
                state.advanceGeneration();
            }
            return new WorkspaceGateway.CommandResult(
                    execution.timedOut()
                            ? WorkspaceGateway.CommandStatus.TIMED_OUT
                            : WorkspaceGateway.CommandStatus.COMPLETED,
                    request.expectedGeneration(),
                    generationBefore,
                    state.generation(),
                    execution.exitCode(),
                    execution.durationMillis(),
                    Objects.requireNonNullElse(execution.stdout(), ""),
                    Objects.requireNonNullElse(execution.stderr(), ""),
                    null,
                    null
            );
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public PatchBatchResult applyPatch(
            WorkspaceId workspaceId,
            WorkspaceMutationCommand command
    ) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");

        LocalWorkspaceRegistry.LocalWorkspaceState state =
                registry.require(workspaceId);

        Lock writeLock = state.lock().writeLock();
        writeLock.lock();

        try {
            long generationBefore = state.generation();

            // stale command: no filesystem operation may occur.
            if (command.expectedGeneration() != generationBefore) {
                return PatchBatchResult.conflict(
                        command,
                        generationBefore,
                        "STALE_WORKSPACE_GENERATION",
                        "Expected generation does not match the current workspace state"
                );
            }

            try {
                requireSafeWorkspaceRoot(state.root());
            } catch (OperationFailure failure) {
                return failedBeforeFirstOperation(
                        command,
                        generationBefore,
                        failure
                );
            }

            List<PatchOperationResult> results = new ArrayList<>();

            for (int index = 0; index < command.operations().size(); index++) {
                PatchOperation operation = command.operations().get(index);

                try {
                    results.add(applyOne(state.root(), operation));
                } catch (OperationFailure failure) {
                    results.add(PatchOperationResult.failed(
                            operation,
                            failure.beforeSha256(),
                            failure.errorCode(),
                            failure.getMessage()
                    ));

                    appendNotAttempted(command, index + 1, results);

                    PatchBatchResult outcome = results.stream()
                            .anyMatch(PatchOperationResult::applied)
                            ? PatchBatchResult.partialSuccess(
                            command,
                            generationBefore,
                            results,
                            "PATCH_OPERATION_FAILED",
                            "A confirmed operation was applied before a later operation failed"
                    )
                            : PatchBatchResult.failed(
                            command,
                            generationBefore,
                            results,
                            "PATCH_OPERATION_FAILED",
                            "The first workspace operation failed"
                    );

                    if (outcome.stateChanged()) {
                        state.advanceGeneration();
                    }
                    return outcome;
                }
            }

            PatchBatchResult outcome = PatchBatchResult.success(
                    command,
                    generationBefore,
                    results
            );
            state.advanceGeneration();
            return outcome;

        } finally {
            writeLock.unlock();
        }
    }

    private PatchBatchResult failedBeforeFirstOperation(
            WorkspaceMutationCommand command,
            long generationBefore,
            OperationFailure failure
    ) {
        List<PatchOperationResult> results = new ArrayList<>();
        PatchOperation first = command.operations().get(0);

        results.add(PatchOperationResult.failed(
                first,
                failure.beforeSha256(),
                failure.errorCode(),
                failure.getMessage()
        ));
        appendNotAttempted(command, 1, results);

        return PatchBatchResult.failed(
                command,
                generationBefore,
                results,
                "PATCH_OPERATION_FAILED",
                "Workspace is not available for mutation"
        );
    }

    private void appendNotAttempted(
            WorkspaceMutationCommand command,
            int startIndex,
            List<PatchOperationResult> results
    ) {
        for (int index = startIndex; index < command.operations().size(); index++) {
            results.add(PatchOperationResult.notAttempted(
                    command.operations().get(index)
            ));
        }
    }

    private Path resolveReadablePath(
            Path workspaceRoot,
            String rawPath,
            boolean allowRoot
    ) {
        Objects.requireNonNull(rawPath, "path must not be null");
        if (allowRoot && ".".equals(rawPath)) {
            return workspaceRoot;
        }
        try {
            return resolveSafeTarget(workspaceRoot, rawPath);
        } catch (OperationFailure failure) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.INVALID_PATH,
                    failure.errorCode(),
                    failure.getMessage(),
                    failure
            );
        }
    }

    private WorkspaceGateway.FileEntry toFileEntry(Path root, Path path) {
        try {
            WorkspaceGateway.FileType type = Files.isDirectory(
                    path,
                    LinkOption.NOFOLLOW_LINKS
            )
                    ? WorkspaceGateway.FileType.DIRECTORY
                    : WorkspaceGateway.FileType.FILE;
            long size = type == WorkspaceGateway.FileType.FILE ? Files.size(path) : 0;
            return new WorkspaceGateway.FileEntry(
                    repositoryPath(root, path),
                    type,
                    size
            );
        } catch (IOException exception) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                    "WORKSPACE_FILE_METADATA_FAILED",
                    "Could not read Workspace file metadata",
                    exception
            );
        }
    }

    private String normalizeDirectoryLabel(String directory) {
        return ".".equals(directory) ? "." : portablePath(Path.of(directory).normalize());
    }

    private String repositoryPath(Path root, Path path) {
        return portablePath(root.relativize(path));
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private boolean isInternalPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        return ".git".equals(first) || ".gitnova".equals(first);
    }

    private byte[] readWorkspaceFile(Path root, Path target) {
        try {
            return readExistingRegularFile(root, target);
        } catch (OperationFailure failure) {
            WorkspaceOperationException.Reason reason = "FILE_NOT_FOUND".equals(
                    failure.errorCode()
            )
                    ? WorkspaceOperationException.Reason.NOT_FOUND
                    : WorkspaceOperationException.Reason.FILESYSTEM_FAILURE;
            throw workspaceFailure(
                    reason,
                    failure.errorCode(),
                    failure.getMessage(),
                    failure
            );
        }
    }

    private String tryDecodeUtf8Text(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private Map<String, byte[]> readWorkspaceTree(Path root) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            List<Path> ordered = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isInternalPath(root, path))
                    .sorted(Comparator.comparing(path -> repositoryPath(root, path)))
                    .toList();
            for (Path path : ordered) {
                files.put(repositoryPath(root, path), Files.readAllBytes(path));
            }
            return files;
        } catch (IOException exception) {
            throw workspaceFailure(
                    WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                    "WORKSPACE_TREE_READ_FAILED",
                    "Could not read Workspace file tree",
                    exception
            );
        }
    }

    private byte[] readBaseBlob(
            LocalWorkspaceRegistry.LocalWorkspaceState state,
            GitObjectId objectId
    ) {
        if (objectId == null) {
            return null;
        }
        try {
            return gitObjectReader.requireBlob(
                    state.repoKey().value(),
                    objectId.value()
            );
        } catch (GitObjectReadException exception) {
            throw mapSnapshotFailure(exception);
        }
    }

    private WorkspaceOperationException mapSnapshotFailure(
            GitObjectReadException exception
    ) {
        return switch (exception.reason()) {
            case NOT_FOUND -> workspaceFailure(
                    WorkspaceOperationException.Reason.SNAPSHOT_UNAVAILABLE,
                    "SNAPSHOT_OBJECT_NOT_FOUND",
                    "Workspace base snapshot object was not found",
                    exception
            );
            case CORRUPT -> workspaceFailure(
                    WorkspaceOperationException.Reason.SNAPSHOT_UNAVAILABLE,
                    "SNAPSHOT_OBJECT_CORRUPT",
                    "Workspace base snapshot object is corrupt",
                    exception
            );
            case TRANSIENT -> workspaceFailure(
                    WorkspaceOperationException.Reason.SNAPSHOT_UNAVAILABLE,
                    "SNAPSHOT_STORAGE_UNAVAILABLE",
                    "Workspace base snapshot storage is temporarily unavailable",
                    exception
            );
        };
    }

    private String workspaceFingerprint(Path root) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
        Map<String, byte[]> files = readWorkspaceTree(root);
        files.forEach((path, bytes) -> {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            digest.update((byte) 0);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static WorkspaceOperationException workspaceFailure(
            WorkspaceOperationException.Reason reason,
            String errorCode,
            String message
    ) {
        return new WorkspaceOperationException(reason, errorCode, message);
    }

    private static WorkspaceOperationException workspaceFailure(
            WorkspaceOperationException.Reason reason,
            String errorCode,
            String message,
            Throwable cause
    ) {
        return new WorkspaceOperationException(reason, errorCode, message, cause);
    }

    private PatchOperationResult applyOne(
            Path workspaceRoot,
            PatchOperation operation
    ) throws OperationFailure {
        Path target = resolveSafeTarget(workspaceRoot, operation.filePath());

        return switch (operation.type()) {
            case CREATE -> createFile(workspaceRoot, target, operation);
            case UPDATE -> updateFile(workspaceRoot, target, operation);
            case DELETE -> deleteFile(workspaceRoot, target, operation);
        };
    }

    private PatchOperationResult createFile(
            Path workspaceRoot,
            Path target,
            PatchOperation operation
    ) throws OperationFailure {
        createSafeParents(workspaceRoot, target.getParent());

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    "FILE_ALREADY_EXISTS",
                    "CREATE target already exists",
                    null,
                    null
            );
        }

        byte[] after = operation.content().getBytes(StandardCharsets.UTF_8);
        writeAtomically(target, after, true, null);

        return PatchOperationResult.applied(
                operation,
                null,
                sha256(after)
        );
    }

    private PatchOperationResult updateFile(
            Path workspaceRoot,
            Path target,
            PatchOperation operation
    ) throws OperationFailure {
        byte[] before = readExistingRegularFile(workspaceRoot, target);
        String beforeSha256 = sha256(before);

        TextFile document = TextFile.parse(before, beforeSha256);
        List<String> patchedLines = applyUnifiedDiff(
                operation.patch(),
                operation.filePath(),
                document.lines(),
                beforeSha256
        );

        byte[] after = document.render(patchedLines)
                .getBytes(StandardCharsets.UTF_8);
        String afterSha256 = sha256(after);

        if (beforeSha256.equals(afterSha256)) {
            throw failure(
                    "PATCH_HAS_NO_EFFECT",
                    "UPDATE patch does not change the file",
                    beforeSha256,
                    null
            );
        }

        writeAtomically(target, after, false, beforeSha256);

        return PatchOperationResult.applied(
                operation,
                beforeSha256,
                afterSha256
        );
    }

    private PatchOperationResult deleteFile(
            Path workspaceRoot,
            Path target,
            PatchOperation operation
    ) throws OperationFailure {
        byte[] before = readExistingRegularFile(workspaceRoot, target);
        String beforeSha256 = sha256(before);

        try {
            Files.delete(target);
        } catch (NoSuchFileException exception) {
            throw failure(
                    "FILE_NOT_FOUND",
                    "DELETE target no longer exists",
                    beforeSha256,
                    exception
            );
        } catch (IOException exception) {
            throw failure(
                    "FILESYSTEM_FAILURE",
                    "Could not delete workspace file",
                    beforeSha256,
                    exception
            );
        }

        return PatchOperationResult.applied(
                operation,
                beforeSha256,
                null
        );
    }

    private Path resolveSafeTarget(
            Path workspaceRoot,
            String rawPath
    ) throws OperationFailure {
        try {
            if (rawPath.startsWith("/")
                    || rawPath.startsWith("\\")
                    || rawPath.contains("\\")
                    || rawPath.contains("//")
                    || rawPath.endsWith("/")) {
                throw failure(
                        "INVALID_WORKSPACE_PATH",
                        "filePath must be a normalized repository-relative path",
                        null,
                        null
                );
            }

            Path relative = Path.of(rawPath);

            if (relative.isAbsolute() || relative.getNameCount() == 0) {
                throw failure(
                        "INVALID_WORKSPACE_PATH",
                        "filePath must be relative to the workspace root",
                        null,
                        null
                );
            }

            for (Path segment : relative) {
                if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                    throw failure(
                            "INVALID_WORKSPACE_PATH",
                            "filePath must not contain dot segments",
                            null,
                            null
                    );
                }
            }

            Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
            Path target = normalizedRoot.resolve(relative).normalize();

            if (target.equals(normalizedRoot) || !target.startsWith(normalizedRoot)) {
                throw failure(
                        "INVALID_WORKSPACE_PATH",
                        "filePath escapes the workspace root",
                        null,
                        null
                );
            }

            if (isInternalPath(normalizedRoot, target)) {
                throw failure(
                        "RESERVED_WORKSPACE_PATH",
                        "Workspace internal paths cannot be accessed",
                        null,
                        null
                );
            }

            rejectSymlinkComponents(normalizedRoot, target);
            return target;

        } catch (InvalidPathException exception) {
            throw failure(
                    "INVALID_WORKSPACE_PATH",
                    "filePath is not a valid local filesystem path",
                    null,
                    exception
            );
        }
    }

    private void requireSafeWorkspaceRoot(Path root) throws OperationFailure {
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    "WORKSPACE_UNAVAILABLE",
                    "Workspace root is not an available local directory",
                    null,
                    null
            );
        }
    }

    private void createSafeParents(
            Path workspaceRoot,
            Path parent
    ) throws OperationFailure {
        try {
            Files.createDirectories(parent);
            rejectSymlinkComponents(workspaceRoot, parent);
        } catch (IOException exception) {
            throw failure(
                    "FILESYSTEM_FAILURE",
                    "Could not create workspace parent directories",
                    null,
                    exception
            );
        }
    }

    private void rejectSymlinkComponents(
            Path workspaceRoot,
            Path target
    ) throws OperationFailure {
        Path current = workspaceRoot;

        if (Files.isSymbolicLink(current)) {
            throw failure(
                    "UNSAFE_WORKSPACE_PATH",
                    "Workspace root must not be a symbolic link",
                    null,
                    null
            );
        }

        Path relative = workspaceRoot.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);

            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw failure(
                        "UNSAFE_WORKSPACE_PATH",
                        "Workspace path contains a symbolic link",
                        null,
                        null
                );
            }
        }
    }

    private byte[] readExistingRegularFile(
            Path workspaceRoot,
            Path target
    ) throws OperationFailure {
        rejectSymlinkComponents(workspaceRoot, target);

        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    "FILE_NOT_FOUND",
                    "Target file does not exist",
                    null,
                    null
            );
        }

        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    "UNSUPPORTED_FILE_TYPE",
                    "Target must be a regular file",
                    null,
                    null
            );
        }

        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw failure(
                    "FILESYSTEM_FAILURE",
                    "Could not read workspace file",
                    null,
                    exception
            );
        }
    }

    private List<String> applyUnifiedDiff(
            String source,
            String filePath,
            List<String> originalLines,
            String beforeSha256
    ) throws OperationFailure {
        try {
            List<String> diffLines = normalizeUnifiedDiff(source, filePath);
            Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);

            // 精确 apply；不使用 applyFuzzy，避免模型把 patch 应用到错误位置。
            return patch.applyTo(originalLines);

        } catch (PatchFailedException | IllegalArgumentException exception) {
            throw failure(
                    "PATCH_DOES_NOT_APPLY",
                    "UPDATE patch does not apply to the current file content",
                    beforeSha256,
                    exception
            );
        }
    }

    private List<String> normalizeUnifiedDiff(
            String source,
            String filePath
    ) throws OperationFailure {
        String[] split = source.split("\\r?\\n", -1);
        List<String> lines = new ArrayList<>(List.of(split));

        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        int hunkIndex = -1;
        int headerIndex = -1;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            if (line.startsWith("--- ")) {
                headerIndex = index;
            }
            if (line.startsWith("@@")) {
                hunkIndex = index;
                break;
            }
        }

        if (hunkIndex < 0) {
            throw failure(
                    "INVALID_UNIFIED_DIFF",
                    "UPDATE patch must contain at least one unified diff hunk",
                    null,
                    null
            );
        }

        // 允许模型只提交 @@ hunk；Gateway 补齐 parser 所需 header。
        if (headerIndex < 0) {
            List<String> normalized = new ArrayList<>();
            normalized.add("--- a/" + filePath);
            normalized.add("+++ b/" + filePath);
            normalized.addAll(lines.subList(hunkIndex, lines.size()));
            return normalized;
        }

        // 去掉 diff --git / index 等 preamble，只保留标准 unified diff 部分。
        return List.copyOf(lines.subList(headerIndex, lines.size()));
    }

    private void writeAtomically(
            Path target,
            byte[] contents,
            boolean createNew,
            String beforeSha256
    ) throws OperationFailure {
        Path temporary = null;

        try {
            Path parent = target.getParent();
            temporary = Files.createTempFile(parent, ".gitnova-write-", ".tmp");

            Files.write(
                    temporary,
                    contents,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            if (createNew) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } else {
                if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(
                            "FILE_NOT_FOUND",
                            "UPDATE target no longer exists",
                            beforeSha256,
                            null
                    );
                }

                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            temporary = null;

        } catch (OperationFailure exception) {
            throw exception;

        } catch (AtomicMoveNotSupportedException exception) {
            throw failure(
                    "ATOMIC_WRITE_UNAVAILABLE",
                    "Filesystem does not support atomic workspace file replacement",
                    beforeSha256,
                    exception
            );

        } catch (java.nio.file.FileAlreadyExistsException exception) {
            throw failure(
                    "FILE_ALREADY_EXISTS",
                    "CREATE target already exists",
                    beforeSha256,
                    exception
            );

        } catch (IOException exception) {
            throw failure(
                    "FILESYSTEM_FAILURE",
                    "Could not write workspace file",
                    beforeSha256,
                    exception
            );

        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Primary operation result must not be hidden by temp-file cleanup failure.
                }
            }
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 must be available in every Java runtime",
                    exception
            );
        }
    }

    private static OperationFailure failure(
            String errorCode,
            String message,
            String beforeSha256,
            Throwable cause
    ) {
        return new OperationFailure(errorCode, message, beforeSha256, cause);
    }

    private static final class OperationFailure extends Exception {

        private final String errorCode;
        private final String beforeSha256;

        private OperationFailure(
                String errorCode,
                String message,
                String beforeSha256,
                Throwable cause
        ) {
            super(message, cause);
            this.errorCode = errorCode;
            this.beforeSha256 = beforeSha256;
        }

        String errorCode() {
            return errorCode;
        }

        String beforeSha256() {
            return beforeSha256;
        }
    }

    private record TextFile(
            List<String> lines,
            String lineSeparator,
            boolean endsWithLineSeparator
    ) {
        static TextFile parse(
                byte[] bytes,
                String beforeSha256
        ) throws OperationFailure {
            String text;

            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw failure(
                        "FILE_NOT_UTF8_TEXT",
                        "UPDATE supports UTF-8 text files only",
                        beforeSha256,
                        exception
                );
            }

            boolean hasCrLf = text.contains("\r\n");
            String withoutCrLf = text.replace("\r\n", "");

            if (withoutCrLf.indexOf('\r') >= 0
                    || (hasCrLf && withoutCrLf.indexOf('\n') >= 0)) {
                throw failure(
                        "MIXED_LINE_ENDINGS",
                        "UPDATE does not support mixed line endings",
                        beforeSha256,
                        null
                );
            }

            String separator = hasCrLf ? "\r\n" : "\n";
            boolean terminated = text.endsWith(separator);

            if (text.isEmpty()) {
                return new TextFile(List.of(), separator, false);
            }

            String[] split = text.split(Pattern.quote(separator), -1);
            int lineCount = terminated ? split.length - 1 : split.length;

            List<String> lines = new ArrayList<>(lineCount);
            for (int index = 0; index < lineCount; index++) {
                lines.add(split[index]);
            }

            return new TextFile(List.copyOf(lines), separator, terminated);
        }

        String render(List<String> nextLines) {
            String result = String.join(lineSeparator, nextLines);

            if (endsWithLineSeparator) {
                result += lineSeparator;
            }
            return result;
        }
    }
}
