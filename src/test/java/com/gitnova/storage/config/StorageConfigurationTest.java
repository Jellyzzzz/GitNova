package com.gitnova.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StorageConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withPropertyValues("gitnova.repo.base-path=build/test-repositories");

    @Test
    void shouldBindRepositoryStorageRoot() {
        contextRunner.run(context -> assertEquals(
                Path.of("build/test-repositories"),
                context.getBean(RepositoryStorageProperties.class).basePath()
        ));
    }

    @Test
    void shouldRejectBlankRepositoryRoot() {
        contextRunner.withPropertyValues("gitnova.repo.base-path=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
