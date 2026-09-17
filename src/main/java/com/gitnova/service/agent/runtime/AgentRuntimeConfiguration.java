package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.context.ObservationPolicy;
import com.gitnova.service.agent.context.ToolObservationPreview;
import com.gitnova.service.agent.context.ContextBudget;
import com.gitnova.service.agent.context.SessionContextService;
import com.gitnova.service.agent.context.TokenEstimator;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelGateway;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.journal.RunJournal;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.storage.artifact.LocalArtifactStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfiguration {
    @Bean
    public AgentRuntimePolicy agentRuntimePolicy(AgentRuntimeProperties properties) {
        return new AgentRuntimePolicy(properties.model(),properties.maxModelCalls(),properties.maxToolCalls(),properties.maxProtocolCorrections(),
                properties.maxFinalDraftCorrections(),properties.maxOutputTokens(),properties.temperature());
    }
    @Bean
    public ObservationPolicy observationPolicy(AgentRuntimeProperties properties){
        return properties.observation();
    }
    @Bean
    public ContextBudget contextBudget(AgentRuntimeProperties properties) {
        return properties.context();
    }
    @Bean
    public AgentRuntime agentRuntime(ModelGateway modelGateway,
                                     PromptAssembler promptAssembler,
                                     MessageFactory messageFactory,
                                     ToolRegistry toolRegistry,
                                     ObjectMapper objectMapper,
                                     WorkspaceGateway workspaceGateway,
                                     ToolSetResolver toolSetResolver,
                                     RunJournal journal,
                                     CanonicalJsonCodec canonicalJson,
                                     LocalArtifactStore artifactStore,
                                     ToolObservationPreview observationPreview,
                                     SessionContextService sessionContexts,
                                     TokenEstimator tokenEstimator) {
        return new AgentRuntime(modelGateway,
                promptAssembler,
                messageFactory,
                toolRegistry,
                workspaceGateway,
                new CompletionInspector(objectMapper, workspaceGateway),
                toolSetResolver,
                journal,
                canonicalJson,
                artifactStore,
                observationPreview,
                sessionContexts,
                tokenEstimator);
    }
}
