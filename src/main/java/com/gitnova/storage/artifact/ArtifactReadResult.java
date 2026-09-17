package com.gitnova.storage.artifact;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One UTF-8 text slice. Offsets are bytes; nextOffset is exclusive. */
public record ArtifactReadResult(
        String artifactId,
        long offset,
        long nextOffset,
        long totalBytes,
        String content,
        boolean hasMore
) {
    public ArtifactReadResult {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(content, "content");
        if (artifactId.isBlank() || offset < 0 || nextOffset < offset || totalBytes < nextOffset) {
            throw new IllegalArgumentException("Invalid artifact range");
        }
        if (hasMore != (nextOffset < totalBytes) || (hasMore && nextOffset == offset)) {
            throw new IllegalArgumentException("Artifact read must report progress and remaining content");
        }
        if (nextOffset - offset != content.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("Artifact offsets must describe the returned UTF-8 bytes");
        }
    }
}
