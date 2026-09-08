package com.gitnova.service.agent.dispatch;

import com.gitnova.service.agent.execution.AgentTaskRunStore;
import com.gitnova.service.agent.execution.DurableRunExecutor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AgentRabbitConfiguration {

    public static final String RUN_EXCHANGE = "gitnova.agent";
    public static final String RUN_QUEUE = "gitnova.agent.run.dispatch";
    public static final String RUN_ROUTING_KEY = "agent.run.dispatch";

    @Bean
    public DirectExchange agentRunExchange() {
        return new DirectExchange(RUN_EXCHANGE, true, false);
    }

    @Bean
    public Queue agentRunQueue() {
        return new Queue(RUN_QUEUE, true);
    }

    @Bean
    public Binding agentRunBinding(
            Queue agentRunQueue,
            DirectExchange agentRunExchange
    ) {
        return BindingBuilder
                .bind(agentRunQueue)
                .to(agentRunExchange)
                .with(RUN_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.gitnova.service.agent.dispatch");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RunDispatchWorker runDispatchWorker(
            AgentTaskRunStore taskRunStore,
            DurableRunExecutor runExecutor
    ) {
        return new RunDispatchWorker(taskRunStore, runExecutor);
    }
}
