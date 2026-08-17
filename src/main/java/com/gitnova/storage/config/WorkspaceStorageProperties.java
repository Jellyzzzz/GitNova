package com.gitnova.storage.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "gitnova.workspace")
@Validated
public record WorkspaceStorageProperties(@NotNull Path basePath) {
    public WorkspaceStorageProperties {
        if (basePath == null || basePath.toString().isBlank()) {
            throw new IllegalArgumentException("workspace basePath must not be blank");
        }
    }
}
