package com.gitnova.storage.artifact;

import java.util.Objects;

public record ArtifactRef(String artifactId, String sha256, long sizeBytes, String mediaType) {
    public ArtifactRef{
        requireNonBlank(artifactId,"artifactId");
        requireNonBlank(sha256,"sha256");
        requireNonBlank(mediaType,"mediaType");
        if(!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 lower-case hex characters");
        if(sizeBytes<0) throw new IllegalArgumentException("sizeBytes must not be negative");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
