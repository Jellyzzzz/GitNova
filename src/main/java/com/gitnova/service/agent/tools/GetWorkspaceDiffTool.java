package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;

import java.util.Objects;

/** Returns the authoritative BASE-to-Workspace diff at one generation. */
public final class GetWorkspaceDiffTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public GetWorkspaceDiffTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "getWorkspaceDiff",
                "Returns the current Workspace changes relative to its trusted BASE snapshot",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        try {
            return ToolResult.success(objectMapper.valueToTree(
                    workspaceGateway.getWorkspaceDiff(execution.requireWorkspaceId())
            ));
        } catch (IllegalStateException exception) {
            return WorkspaceToolResults.missingContext();
        } catch (WorkspaceOperationException exception) {
            return WorkspaceToolResults.error(exception);
        }
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
