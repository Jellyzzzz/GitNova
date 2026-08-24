package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceAgentToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void shouldRegisterWorkspaceToolsWhenGatewayExists() {
        contextRunner
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
    void shouldNotRegisterWorkspaceToolsWithoutGateway() {
        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(WorkspaceAgentToolConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(ListFilesTool.class));
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
                        WorkspaceMutationCommand command
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
