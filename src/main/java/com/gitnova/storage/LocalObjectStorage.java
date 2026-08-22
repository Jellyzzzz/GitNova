package com.gitnova.storage;

import com.gitnova.gitobject.GitObjectHasher;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.storage.config.RepositoryStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Local content-addressed storage with atomic, no-overwrite object visibility. */
@Component
@ConditionalOnProperty(name = "gitnova.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final ObjectPathResolver pathResolver;

    public LocalObjectStorage(RepositoryStorageProperties properties) {
        this.pathResolver = new ObjectPathResolver(properties.basePath());
    }

    @Override
    public void writeObject(String repoKey, String sha1, byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        RepoKey key = RepoKey.parseCanonical(repoKey);
        GitObjectId id = GitObjectId.of(sha1);
        verifyDigest(content, id);
        Path directory = requireObjectDirectory(key);
        Path temporary = createTemporaryFile(directory, id);
        try {
            writeAndForce(temporary, content);
            publishOrVerifyExisting(key, id, temporary);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        }
    }

    @Override
    public void promoteObject(String repoKey, String sha1, Path verifiedTemporaryFile) {
        Objects.requireNonNull(verifiedTemporaryFile, "verifiedTemporaryFile must not be null");
        RepoKey key = RepoKey.parseCanonical(repoKey);
        GitObjectId id = GitObjectId.of(sha1);
        if (!Files.isRegularFile(verifiedTemporaryFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                    "temporary object is not a regular file");
        }
        Path directory = requireObjectDirectory(key);
        Path temporary = createTemporaryFile(directory, id);
        try {
            copyAndForce(verifiedTemporaryFile, temporary, id);
            publishOrVerifyExisting(key, id, temporary);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            throw exception;
        }
    }

    @Override
    public byte[] readObject(String repoKey, String sha1) {
        RepoKey key = RepoKey.parseCanonical(repoKey);
        GitObjectId id = GitObjectId.of(sha1);
        Path path = requireObjectDirectory(key).resolve(id.value());
        requireRegularObject(path, id);
        try {
            byte[] content = Files.readAllBytes(path);
            verifyDigest(content, id);
            return content;
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to read object: " + id.value(), exception);
        }
    }

    @Override
    public InputStream openObject(String repoKey, String sha1) {
        RepoKey key = RepoKey.parseCanonical(repoKey);
        GitObjectId id = GitObjectId.of(sha1);
        Path path = requireObjectDirectory(key).resolve(id.value());
        requireRegularObject(path, id);
        try {
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to open object: " + id.value(), exception);
        }
    }

    @Override
    public boolean existsObject(String repoKey, String sha1) {
        RepoKey key = RepoKey.parseCanonical(repoKey);
        GitObjectId id = GitObjectId.of(sha1);
        Path path = requireObjectDirectory(key).resolve(id.value());
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public Set<String> listObjects(String repoKey) {
        RepoKey key = RepoKey.parseCanonical(repoKey);
        Path directory = findExistingObjectDirectory(key);
        if (directory == null) {
            return Set.of();
        }
        Set<String> objectIds = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && GitObjectId.isValid(name)) {
                    objectIds.add(name);
                }
            }
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to list objects for repository: " + key.value(), exception);
        }
        return Set.copyOf(objectIds);
    }

    private Path findExistingObjectDirectory(RepoKey key) {
        Path directory = pathResolver.objectDirectory(key);
        Path root = pathResolver.storageRoot();
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObjectStorageException(
                    ObjectStorageException.Reason.CORRUPT,
                    "storage root is not a safe directory"
            );
        }
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStorageException(
                        ObjectStorageException.Reason.CORRUPT,
                        "object directory contains an unsafe path component"
                );
            }
        }
        return directory;
    }

    private Path requireObjectDirectory(RepoKey key) {
        Path directory = pathResolver.objectDirectory(key);
        Path root = pathResolver.storageRoot();
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(root);
            }
            if (Files.isSymbolicLink(root)) {
                throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                        "storage root must not be a symbolic link");
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                        "storage root is not a directory");
            }
            Path current = root;
            for (Path part : root.relativize(directory)) {
                current = current.resolve(part);
                if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(current);
                    } catch (FileAlreadyExistsException ignored) {
                        // Another writer created the same directory; validate it below.
                    }
                }
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                            "object directory contains an unsafe path component");
                }
            }
            return directory;
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to prepare object directory", exception);
        }
    }

    private Path createTemporaryFile(Path directory, GitObjectId id) {
        try {
            return Files.createTempFile(directory, "." + id.value() + ".", ".tmp");
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to create object staging file", exception);
        }
    }

    private void writeAndForce(Path destination, byte[] content) {
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to write object staging file", exception);
        }
    }

    private void copyAndForce(Path source, Path destination, GitObjectId expectedId) {
        MessageDigest digest = sha1Digest();
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to copy verified object", exception);
        }
        force(destination);
        verifyRawDigest(digest.digest(), expectedId);
    }

    private void publishOrVerifyExisting(RepoKey key, GitObjectId id, Path temporary) {
        Path target = pathResolver.objectPath(key, id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifySameExistingObject(target, temporary, id);
            deleteQuietly(temporary);
            return;
        }
        try {
            /* Creating a link publishes a new name atomically and never replaces an existing object. */
            Files.createLink(target, temporary);
            deleteQuietly(temporary);
        } catch (FileAlreadyExistsException exception) {
            verifySameExistingObject(target, temporary, id);
            deleteQuietly(temporary);
        } catch (UnsupportedOperationException | IOException linkFailure) {
            publishWithAtomicMoveFallback(target, temporary, id);
        }
    }

    private void publishWithAtomicMoveFallback(Path target, Path temporary, GitObjectId id) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            verifyStoredObject(target, id);
        } catch (FileAlreadyExistsException exception) {
            verifySameExistingObject(target, temporary, id);
            deleteQuietly(temporary);
        } catch (IOException moveFailure) {
            deleteQuietly(temporary);
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "atomic object promotion is unavailable", moveFailure);
        }
    }

    private void verifySameExistingObject(Path target, Path candidate, GitObjectId expectedId) {
        requireRegularObject(target, expectedId);
        verifyStoredObject(target, expectedId);
        try {
            if (Files.mismatch(target, candidate) != -1L) {
                throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                        "existing object content conflicts with declared SHA-1");
            }
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to compare existing object", exception);
        }
    }

    private void verifyStoredObject(Path path, GitObjectId expectedId) {
        try {
            verifyDigest(Files.readAllBytes(path), expectedId);
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to verify stored object", exception);
        }
    }

    private static void verifyDigest(byte[] content, GitObjectId expectedId) {
        if (!GitObjectHasher.sha1(content).equals(expectedId)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                    "object content digest does not match its SHA-1");
        }
    }

    private static void verifyRawDigest(byte[] digest, GitObjectId expectedId) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        if (!GitObjectId.of(hex.toString()).equals(expectedId)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                    "object content digest does not match its SHA-1");
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required by the Java runtime", exception);
        }
    }

    private static void force(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException exception) {
            throw new ObjectStorageException(ObjectStorageException.Reason.TRANSIENT,
                    "failed to fsync object staging file", exception);
        }
    }

    private static void requireRegularObject(Path path, GitObjectId id) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.NOT_FOUND,
                    "object not found: " + id.value());
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObjectStorageException(ObjectStorageException.Reason.CORRUPT,
                    "object path is not a regular file: " + id.value());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The file is in a controlled object directory and is unreachable; later cleanup can remove it.
        }
    }
}
