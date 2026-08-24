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

import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_PATH_CHARS;

/** Lists the direct children of one Workspace directory. */
public final class ListFilesTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public ListFilesTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("path")
                .put("type", "string")
                .put("maxLength", MAX_PATH_CHARS);
        schema.putArray("required").add("path");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "listFiles",
                "Lists direct file and directory children in the current Workspace",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        String path = arguments.path("path").asText();
        if (path.isBlank()) {
            return WorkspaceToolResults.invalid("INVALID_DIRECTORY_PATH", "path must not be blank");
        }
        try {
            WorkspaceGateway.FileListing listing = workspaceGateway.listFiles(
                    execution.requireWorkspaceId(),
                    path
            );
            return ToolResult.success(
                    objectMapper.valueToTree(listing),
                    listing.truncated()
            );
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
