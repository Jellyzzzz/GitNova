package com.gitnova.service.agent.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Captures the repository-visible file content of a local Workspace. */
public final class WorkspaceTreeFingerprint {

    private WorkspaceTreeFingerprint() {
    }

    public static String capture(Path workspaceRoot) {
        Path root = requireSafeRoot(workspaceRoot);
        MessageDigest digest = sha256Digest();
        long totalBytes = 0;
        byte[] buffer = new byte[64 * 1024];

        for (Path path : regularWorkspaceFiles(root)) {
            String repositoryPath = portablePath(root.relativize(path));
            digest.update(repositoryPath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);

            try (InputStream input = Channels.newInputStream(Files.newByteChannel(
                    path,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS
            ))) {
                while (true) {
                    int count = input.read(buffer);
                    if (count == -1) {
                        break;
                    }
                    totalBytes += count;
                    if (totalBytes > WorkspaceGateway.MAX_FINGERPRINT_BYTES) {
                        throw failure(
                                WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                                "WORKSPACE_FINGERPRINT_TOO_LARGE",
                                "Workspace exceeds the fingerprint byte limit"
                        );
                    }
                    digest.update(buffer, 0, count);
                }
            } catch (IOException exception) {
                throw failure(
                        WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                        "WORKSPACE_FINGERPRINT_FAILED",
                        "Could not fingerprint Workspace state",
                        exception
                );
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path requireSafeRoot(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new NullPointerException("workspaceRoot must not be null");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    WorkspaceOperationException.Reason.WORKSPACE_UNAVAILABLE,
                    "WORKSPACE_ROOT_UNAVAILABLE",
                    "Workspace root must be an existing non-symbolic-link directory"
            );
        }
        return root;
    }

    private static List<Path> regularWorkspaceFiles(Path root) {
        try (var paths = Files.walk(root)) {
            List<Path> collected = new ArrayList<>();
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(path)
                        && !isInternalPath(root, path)) {
                    if (collected.size() == WorkspaceGateway.MAX_WORKSPACE_FILES) {
                        throw failure(
                                WorkspaceOperationException.Reason.UNSUPPORTED_CONTENT,
                                "WORKSPACE_FILE_COUNT_EXCEEDED",
                                "Workspace exceeds the supported file count"
                        );
                    }
                    collected.add(path);
                }
            }
            collected.sort(Comparator.comparing(path -> portablePath(root.relativize(path))));
            return collected;
        } catch (IOException exception) {
            throw failure(
                    WorkspaceOperationException.Reason.FILESYSTEM_FAILURE,
                    "WORKSPACE_TREE_READ_FAILED",
                    "Could not enumerate Workspace files",
                    exception
            );
        }
    }

    private static boolean isInternalPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        return ".git".equals(first) || ".gitnova".equals(first);
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static WorkspaceOperationException failure(
            WorkspaceOperationException.Reason reason,
            String errorCode,
            String message
    ) {
        return new WorkspaceOperationException(reason, errorCode, message);
    }

    private static WorkspaceOperationException failure(
            WorkspaceOperationException.Reason reason,
            String errorCode,
            String message,
            Throwable cause
    ) {
        return new WorkspaceOperationException(reason, errorCode, message, cause);
    }
}
