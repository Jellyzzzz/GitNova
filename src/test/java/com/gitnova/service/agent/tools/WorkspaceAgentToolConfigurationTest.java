package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.service.agent.workspace.LocalWorkspaceConfiguration;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import com.gitnova.service.agent.workspace.WorkspaceCommandExecutor;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkspaceAgentToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void shouldRegisterWorkspaceToolsWhenGatewayExists() {
        contextRunner
                .withPropertyValues("gitnova.workspace.docker.enabled=true")
                .withUserConfiguration(
                        TestDependencies.class,
                        WorkspaceAgentToolConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ListFilesTool.class);
                    assertThat(context).hasSingleBean(FindFilesTool.class);
                    assertThat(context).hasSingleBean(SearchTextTool.class);
                    assertThat(context).hasSingleBean(GetWorkspaceDiffTool.class);
                    assertThat(context).hasSingleBean(ApplyPatchTool.class);
                    assertThat(context).hasSingleBean(RunCommandTool.class);
                });
    }

    @Test
    void shouldRegisterRunCommandFromTheProductionDockerConfiguration() {
        contextRunner
                .withPropertyValues("gitnova.workspace.docker.enabled=true")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AgentWorkspaceMapper.class, () -> mock(AgentWorkspaceMapper.class))
                .withBean(GitObjectReader.class, () -> mock(GitObjectReader.class))
                .withBean(
                        WorkspaceStorageProperties.class,
                        () -> new WorkspaceStorageProperties(Path.of("target/workspaces"))
                )
                .withUserConfiguration(
                        WorkspaceAgentToolConfiguration.class,
                        LocalWorkspaceConfiguration.class
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkspaceCommandExecutor.class);
                    assertThat(context).hasSingleBean(WorkspaceGateway.class);
                    assertThat(context).hasSingleBean(RunCommandTool.class);
                });
    }

    @Test
    void shouldFailStartupWithoutRequiredWorkspaceGateway() {
        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(WorkspaceAgentToolConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("WorkspaceGateway");
                });
    }

    @Test
    void shouldNotExposeRunCommandWhenDockerExecutionIsDisabled() {
        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(
                        WorkspaceGateway.class,
                        () -> (workspaceId, executionPermit, command) -> {
                            throw new UnsupportedOperationException();
                        }
                )
                .withUserConfiguration(WorkspaceAgentToolConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplyPatchTool.class);
                    assertThat(context).doesNotHaveBean(RunCommandTool.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WorkspaceGateway workspaceGateway() {
            return new WorkspaceGateway() {
                @Override
                public PatchBatchResult applyPatch(
                        WorkspaceId workspaceId,
                        WorkspaceExecutionPermit executionPermit,
                        WorkspaceMutationCommand command
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        WorkspaceCommandExecutor workspaceCommandExecutor() {
            return (workspaceRoot, workingDirectory, argv, timeout) -> {
                throw new UnsupportedOperationException();
            };
        }
    }
}
