package com.gitnova.storage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        RepositoryStorageProperties.class,
        WorkspaceStorageProperties.class,
        ArtifactStorageProperties.class
})
public class StorageConfiguration {
}
