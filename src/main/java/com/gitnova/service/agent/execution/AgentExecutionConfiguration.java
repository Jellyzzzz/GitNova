package com.gitnova.service.agent.execution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AgentExecutionConfiguration {

    @Bean("agentHeartbeatScheduler")
    public ThreadPoolTaskScheduler agentHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("agent-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);

        return scheduler;
    }
}
