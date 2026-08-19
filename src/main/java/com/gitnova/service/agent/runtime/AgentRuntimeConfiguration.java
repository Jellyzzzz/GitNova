package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.review.ReviewVerifier;
import com.gitnova.service.agent.tool.ToolRegistry;
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
    public ReviewVerifier reviewVerifier(){
        return new ReviewVerifier();
    }

    @Bean
    public AgentRuntime agentRuntime(ModelGateway modelGateway,
                                     PromptAssembler promptAssembler,
                                     MessageFactory messageFactory,
                                     ToolRegistry toolRegistry,
                                     ReviewVerifier reviewVerifier,
                                     ObjectMapper objectMapper,
                                     AgentRuntimePolicy policy){
        return new AgentRuntime(modelGateway,
                promptAssembler,
                messageFactory,
                toolRegistry,
                reviewVerifier,
                objectMapper,
                policy);
    }
}
