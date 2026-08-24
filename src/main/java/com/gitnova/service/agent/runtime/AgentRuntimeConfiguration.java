package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfiguration {
    @Bean
    public AgentRuntimePolicy agentRuntimePolicy(AgentRuntimeProperties properties){
        return new AgentRuntimePolicy(properties.model(),properties.maxModelCalls(),properties.maxToolCalls(),properties.maxProtocolCorrections(),
                properties.maxFinalDraftCorrections(),properties.maxOutputTokens(),properties.temperature());
    }

    @Bean
    @ConditionalOnBean(WorkspaceGateway.class)
    public AgentRuntime agentRuntime(ModelGateway modelGateway,
                                     PromptAssembler promptAssembler,
                                     MessageFactory messageFactory,
                                     ToolRegistry toolRegistry,
                                     ObjectMapper objectMapper,
                                     WorkspaceGateway workspaceGateway,
                                     AgentRuntimePolicy policy){
        return new AgentRuntime(modelGateway,
                promptAssembler,
                messageFactory,
                toolRegistry,
                new CompletionInspector(objectMapper, workspaceGateway),
                policy);
    }
}
