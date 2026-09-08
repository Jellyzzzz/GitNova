package com.gitnova.service.agent.dispatch;

import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentRabbitConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentRabbitConfiguration.class)
            .withBean(AgentTaskRunStore.class, () -> mock(AgentTaskRunStore.class))
            .withBean(DurableRunExecutor.class, () -> mock(DurableRunExecutor.class));

    @Test
    void shouldRegisterWorkerFromDurableExecutionBoundary() {
        contextRunner.run(context ->
                assertThat(context)
                        .hasSingleBean(RunDispatchWorker.class)
        );
    }

    @Test
    void shouldFailStartupWhenDurableExecutorIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentRabbitConfiguration.class)
                .withBean(
                        AgentTaskRunStore.class,
                        () -> mock(AgentTaskRunStore.class)
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DurableRunExecutor");
                });
    }
}
