package com.gitnova.storage.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withPropertyValues(
                            "gitnova.repo.base-path=build/test-repositories",
                            "gitnova.workspace.base-path=build/test-workspaces",
                            "gitnova.agent.artifact.base-path=build/test-artifacts"
                    );

    @Test
    void shouldBindIndependentStorageRoots() {
        contextRunner.run(context -> {
            assertEquals(
                    Path.of("build/test-repositories"),
                    context.getBean(RepositoryStorageProperties.class).basePath()
            );
            assertEquals(
                    Path.of("build/test-workspaces"),
                    context.getBean(WorkspaceStorageProperties.class).basePath()
            );
            assertEquals(
                    Path.of("build/test-artifacts"),
                    context.getBean(ArtifactStorageProperties.class).basePath()
            );
        });
    }
}
