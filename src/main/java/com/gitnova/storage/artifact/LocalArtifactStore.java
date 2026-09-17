package com.gitnova.storage.artifact;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.storage.config.ArtifactStorageProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, Session-scoped files outside the mutable Workspace. Authorization belongs to the journal. */
@Component
public final class LocalArtifactStore {
    private final ArtifactStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final Path root;

    public LocalArtifactStore(ArtifactStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            Files.createDirectories(properties.basePath());
            root = properties.basePath().toRealPath();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot initialize artifact storage", exception);
        }
    }

    /** The caller must supply the permitted persisted representation, not secrets from raw execution args. */
    public ArtifactRef saveToolResult(AgentExecutionContext context, ToolResult result) throws IOException {
        Objects.requireNonNull(result, "result");
        Path directory = directory(context, true);
        Path temporary = Files.createTempFile(directory, ".artifact-", ".tmp");
        try {
            MessageDigest digest = sha256();
            // Serialize directly to a bounded stream, without another full byte[] copy of the result.
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                OutputStream output = new FilterOutputStream(
                        new DigestOutputStream(Channels.newOutputStream(channel), digest)) {
                    private long written;

                    @Override
                    public void write(int value) throws IOException {
                        if (written >= properties.maxArtifactBytes()) {
                            throw new IOException("Artifact exceeds storage limit");
                        }
                        out.write(value);
                        written++;
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (length > properties.maxArtifactBytes() - written) {
                            throw new IOException("Artifact exceeds storage limit");
                        }
                        out.write(bytes, offset, length);
                        written += length;
                    }
                };
                objectMapper.writerWithDefaultPrettyPrinter()
                        .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET).writeValue(output, result);
                output.flush();
                channel.force(true);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            ArtifactRef reference = new ArtifactRef(hash, hash, Files.size(temporary), "application/json");
            Path target = directory.resolve(hash + ".json");
            try {
                // Same-filesystem hard link publishes the complete file atomically, without replacement.
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException existing) {
                // Deduplication must not silently accept an existing corrupt file.
                read(context, reference, 0, 4);
            }
            Files.delete(temporary);
            try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
                directoryChannel.force(true);
            }
            return reference;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** reference must come from a committed, authorized journal record, never directly from model args. */
    public ArtifactReadResult read(AgentExecutionContext context, ArtifactRef reference,
                                   long offset, int maxBytes) throws IOException {
        Objects.requireNonNull(reference, "reference");
        if (!reference.artifactId().matches("[0-9a-f]{64}")
                || !reference.artifactId().equals(reference.sha256())
                || !"application/json".equals(reference.mediaType())) {
            throw new IllegalArgumentException("Unsupported artifact reference");
        }
        if (offset < 0 || offset > reference.sizeBytes() || maxBytes < 4
                || maxBytes > properties.maxReadBytes()) {
            throw new IllegalArgumentException("Invalid artifact read range");
        }
        if (reference.sizeBytes() > properties.maxArtifactBytes()) {
            throw new IOException("Artifact exceeds storage limit");
        }
        Path path = directory(context, false).resolve(reference.artifactId() + ".json");
        if (Files.isSymbolicLink(path)) throw new IOException("Artifact must not be a symbolic link");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact must be a regular file");
        }

        MessageDigest digest = sha256();
        ByteArrayOutputStream selected = new ByteArrayOutputStream(maxBytes);
        long total = 0;
        // Verify the full digest while retaining only the requested range. Memory is bounded by maxReadBytes.
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > properties.maxArtifactBytes() - total) {
                    throw new IOException("Artifact exceeds storage limit");
                }
                digest.update(buffer, 0, count);
                long from = Math.max(total, offset);
                long to = Math.min(total + count, offset + (long) maxBytes);
                if (to > from) selected.write(buffer, (int) (from - total), (int) (to - from));
                total += count;
            }
        }
        if (total != reference.sizeBytes()
                || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256())) {
            throw new IOException("Artifact integrity check failed");
        }
        byte[] bytes = selected.toByteArray();
        if (bytes.length > 0 && (bytes[0] & 0xc0) == 0x80) {
            throw new IllegalArgumentException("offset must be a UTF-8 character boundary; use nextOffset");
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer content = CharBuffer.allocate(bytes.length);
        var decoded = decoder.decode(input, content, offset + bytes.length == total);
        if (decoded.isError()) decoded.throwException();
        long nextOffset = offset + input.position();
        content.flip();
        return new ArtifactReadResult(reference.artifactId(), offset, nextOffset,
                total, content.toString(), nextOffset < total);
    }

    public int maxReadBytes() {
        return properties.maxReadBytes();
    }

    private Path directory(AgentExecutionContext context, boolean create) throws IOException {
        Objects.requireNonNull(context, "context");
        if (!root.toRealPath().equals(root)) throw new IOException("Artifact storage root changed");
        String scope = context.context().repoKey() + "\n" + context.sessionId();
        String namespace = HexFormat.of().formatHex(sha256().digest(scope.getBytes(StandardCharsets.UTF_8)));
        Path directory = root.resolve(namespace);
        if (create) {
            try {
                Files.createDirectory(directory);
                try (FileChannel channel = FileChannel.open(root, StandardOpenOption.READ)) {
                    channel.force(true);
                }
            } catch (FileAlreadyExistsException existing) {
                // Validate the existing entry below, including the no-symlink boundary.
            }
        }
        if (Files.isSymbolicLink(directory)) throw new IOException("Artifact namespace must not be a symbolic link");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact namespace must be a directory");
        }
        return directory;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
