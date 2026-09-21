package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;

import java.util.Objects;

import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_QUERY_CHARS;

/** Performs literal text search across UTF-8 Workspace files. */
public final class SearchTextTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;
    private final ArtifactTextReader artifactReader;

    public SearchTextTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this(workspaceGateway, objectMapper, null);
    }

    public SearchTextTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper, ArtifactTextReader artifactReader) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.artifactReader = artifactReader;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("maxLength", MAX_QUERY_CHARS);
        properties.putObject("caseSensitive").put("type", "boolean");
        properties.putObject("path").put("type", "string").put("maxLength", 4096);
        properties.putObject("cursor").put("type", "string").put("maxLength", 256);
        schema.putArray("required").add("query").add("caseSensitive");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "searchText",
                "Literal text search. Without path, searches the current Workspace. An issued artifact://tool-results/<step>/<view> "
                        + "path searches captured historical evidence only. Follow nextRequest for more results. cursor is Artifact-only.",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || !arguments.path("query").isTextual()
                || !arguments.path("caseSensitive").isBoolean()) {
            return WorkspaceToolResults.invalid("INVALID_SEARCH_ARGUMENTS", "query and caseSensitive are required");
        }
        String query = arguments.path("query").asText();
        if (query.isEmpty()) {
            return WorkspaceToolResults.invalid("EMPTY_SEARCH_QUERY", "query must not be empty");
        }
        if (query.length() > MAX_QUERY_CHARS) return WorkspaceToolResults.invalid("SEARCH_QUERY_TOO_LARGE", "query is too long");
        if (arguments.has("path")) {
            if (!arguments.path("path").isTextual() || !arguments.path("path").asText().startsWith("artifact:")) {
                return WorkspaceToolResults.invalid("INVALID_SEARCH_PATH", "Omit path for Workspace search, or use an issued Artifact path");
            }
            if (artifactReader == null) return ToolResult.error(ToolStatus.INTERNAL_ERROR, "ARTIFACT_READER_UNAVAILABLE",
                    "Historical resource reading is not configured", false);
            return artifactReader.search(execution, arguments.path("path").asText(), arguments);
        }
        if (arguments.has("cursor")) return WorkspaceToolResults.invalid("INVALID_SEARCH_ARGUMENTS", "cursor requires an Artifact path");
        try {
            WorkspaceGateway.TextSearch search = workspaceGateway.searchText(
                    execution.requireWorkspaceId(),
                    query,
                    arguments.path("caseSensitive").asBoolean()
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
