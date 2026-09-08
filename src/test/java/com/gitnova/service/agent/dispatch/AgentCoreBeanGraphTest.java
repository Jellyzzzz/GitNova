package com.gitnova.service.agent.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DefaultDurableRunExecutor;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.runtime.AgentRuntime;
import com.gitnova.service.agent.runtime.AgentRuntimeConfiguration;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.session.AgentSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentCoreBeanGraphTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "gitnova.agent.runtime.model=test-model",
                            "gitnova.agent.runtime.max-model-calls=2",
                            "gitnova.agent.runtime.max-tool-calls=4",
                            "gitnova.agent.runtime.max-protocol-corrections=1",
                            "gitnova.agent.runtime.max-final-draft-corrections=1",
                            "gitnova.agent.runtime.max-output-tokens=128",
                            "gitnova.agent.runtime.temperature=0.0"
                    )
                    .withUserConfiguration(
                            AgentRabbitConfiguration.class,
                            DefaultDurableRunExecutor.class,
                            AgentRuntimeConfiguration.class,
                            TestDependencies.class
                    );

    @Test
    void shouldRegisterTheRequiredCloudAgentExecutionChain() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkspaceGateway.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(DurableRunExecutor.class);
            assertThat(context).hasSingleBean(RunDispatchWorker.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ModelGateway modelGateway() {
            return mock(ModelGateway.class);
        }

        @Bean
        PromptAssembler promptAssembler() {
            return mock(PromptAssembler.class);
        }

        @Bean
        MessageFactory messageFactory() {
            return mock(MessageFactory.class);
        }

        @Bean
        ToolRegistry toolRegistry() {
            return mock(ToolRegistry.class);
        }

        @Bean
        WorkspaceGateway workspaceGateway() {
            return mock(WorkspaceGateway.class);
        }

        @Bean
        ToolSetResolver toolSetResolver() {
            return mock(ToolSetResolver.class);
        }

        @Bean
        AgentTaskRunStore agentTaskRunStore() {
            return mock(AgentTaskRunStore.class);
        }

        @Bean
        AgentSessionStore agentSessionStore() {
            return mock(AgentSessionStore.class);
        }

        @Bean("agentHeartbeatScheduler")
        TaskScheduler agentHeartbeatScheduler() {
            return mock(TaskScheduler.class);
        }
    }
}
