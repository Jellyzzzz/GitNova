package com.gitnova.storage.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "gitnova.agent.artifact")
@Validated
public record ArtifactStorageProperties(
        @NotNull Path basePath,
        @DefaultValue("8388608") long maxArtifactBytes,
        @DefaultValue("4096") int maxReadBytes
) {
    public ArtifactStorageProperties {
        if (basePath == null || basePath.toString().isBlank()) {
            throw new IllegalArgumentException("artifact basePath must not be blank");
        }
        if (maxArtifactBytes < 4 || maxReadBytes < 4 || maxReadBytes > maxArtifactBytes
                || maxReadBytes > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid artifact storage/read limits");
        }
    }
}
