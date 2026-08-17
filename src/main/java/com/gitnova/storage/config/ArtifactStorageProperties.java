package com.gitnova.storage.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "gitnova.agent.artifact")
@Validated
public record ArtifactStorageProperties(@NotNull Path basePath) {
    public ArtifactStorageProperties {
        if (basePath == null || basePath.toString().isBlank()) {
            throw new IllegalArgumentException("artifact basePath must not be blank");
        }
    }
}
