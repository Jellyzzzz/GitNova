package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties(DockerWorkspaceProperties.class)
public class LocalWorkspaceConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "gitnova.workspace.docker", name = "enabled", havingValue = "true")
    public WorkspaceCommandExecutor workspaceCommandExecutor(DockerWorkspaceProperties properties) {
        return new DockerWorkspaceCommandExecutor(properties);
    }

    @Bean
    public LocalWorkspaceRegistry localWorkspaceRegistry(
            AgentWorkspaceMapper workspaceMapper,
            WorkspaceStorageProperties storageProperties
    ) {
        return new LocalWorkspaceRegistry(workspaceMapper, storageProperties);
    }

    @Bean
    public WorkspaceGateway workspaceGateway(
            LocalWorkspaceRegistry registry,
            GitObjectReader gitObjectReader,
            ObjectProvider<WorkspaceCommandExecutor> commandExecutor
    ) {
        return new LocalWorkspaceGateway(
                registry,
                gitObjectReader,
                commandExecutor.getIfAvailable()
        );
    }
}
