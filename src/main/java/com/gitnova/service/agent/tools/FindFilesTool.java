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

import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_GLOB_CHARS;

/** Finds Workspace files by repository-relative glob. */
public final class FindFilesTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public FindFilesTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("glob")
                .put("type", "string")
                .put("maxLength", MAX_GLOB_CHARS);
        schema.putArray("required").add("glob");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "findFiles",
                "Finds files in the current Workspace by repository-relative glob",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        String glob = arguments.path("glob").asText();
        if (glob.isBlank()) {
            return WorkspaceToolResults.invalid("INVALID_FILE_GLOB", "glob must not be blank");
        }
        try {
            WorkspaceGateway.FileSearch search = workspaceGateway.findFiles(
                    execution.requireWorkspaceId(),
                    glob
            );
            return ToolResult.success(
                    objectMapper.valueToTree(search),
                    search.truncated()
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
