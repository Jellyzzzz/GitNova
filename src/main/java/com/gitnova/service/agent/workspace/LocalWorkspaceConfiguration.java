package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.mapper.agent.AgentWorkspaceMapper;
import com.gitnova.storage.config.WorkspaceStorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocalWorkspaceConfiguration {

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
