package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Workspace-backed tools required by the Cloud Agent runtime. */
@Configuration
public class WorkspaceAgentToolConfiguration {

    @Bean
    public ListFilesTool listFilesTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new ListFilesTool(workspaceGateway, objectMapper);
    }

    @Bean
    public FindFilesTool findFilesTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new FindFilesTool(workspaceGateway, objectMapper);
    }

    @Bean
    public SearchTextTool searchTextTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new SearchTextTool(workspaceGateway, objectMapper);
    }

    @Bean
    public GetWorkspaceDiffTool getWorkspaceDiffTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new GetWorkspaceDiffTool(workspaceGateway, objectMapper);
    }

    @Bean
    public ApplyPatchTool applyPatchTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new ApplyPatchTool(workspaceGateway, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "gitnova.workspace.docker",
            name = "enabled",
            havingValue = "true"
    )
    public RunCommandTool runCommandTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        return new RunCommandTool(workspaceGateway, objectMapper);
    }
}
