package com.gitnova.storage.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "gitnova.repo")
@Validated
public record RepositoryStorageProperties(
        @NotNull Path basePath
) {
    public RepositoryStorageProperties {
        if (basePath == null || basePath.toString().isBlank()) {
            throw new IllegalArgumentException("repo basePath must not be blank");
        }
    }
}