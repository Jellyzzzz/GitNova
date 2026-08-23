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

/** Performs literal text search across UTF-8 Workspace files. */
public final class SearchTextTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public SearchTextTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string");
        properties.putObject("caseSensitive").put("type", "boolean");
        schema.putArray("required").add("query").add("caseSensitive");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "searchText",
                "Searches literal text in UTF-8 files in the current Workspace",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        String query = arguments.path("query").asText();
        if (query.isEmpty()) {
            return WorkspaceToolResults.invalid("EMPTY_SEARCH_QUERY", "query must not be empty");
        }
        try {
            return ToolResult.success(objectMapper.valueToTree(
                    workspaceGateway.searchText(
                            execution.requireWorkspaceId(),
                            query,
                            arguments.path("caseSensitive").asBoolean()
                    )
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
