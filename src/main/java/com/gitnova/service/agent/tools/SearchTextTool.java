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

import static com.gitnova.service.agent.tools.ArtifactTextReader.SESSION_RESULTS_PATH;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_QUERY_CHARS;

/** Literal search in the current Workspace, a historical result, or the Session result collection. */
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
        properties.putObject("path").put("type", "string").put("maxLength", 4096)
                .put("description", "Omit for Workspace search. Use artifact://tool-results/ to discover historical results in this Session, "
                        + "or an issued artifact://tool-results/<step>/<view> to search one result.");
        properties.putObject("cursor").put("type", "string").put("maxLength", 256);
        schema.putArray("required").add("query").add("caseSensitive");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "searchText",
                "Literal text search. Without path, searches the current Workspace. path=artifact://tool-results/ searches this Session's "
                        + "committed historical Tool Results, including small inline results no longer visible after summarization. "
                        + "An issued artifact://tool-results/<step>/<view> searches one result. History matches include source Task/Step, "
                        + "historical generation when known, and a readRequest for readFile. Read-back copies are excluded from collection search. "
                        + "This reads captured evidence; it does not re-execute commands or validate the current Workspace. "
                        + "Follow nextRequest while searchComplete=false, even if the current page has no matches. cursor is history-only.",
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
            JsonNode path = arguments.get("path");
            if (!path.isTextual() || !path.asText().startsWith(SESSION_RESULTS_PATH)) {
                return WorkspaceToolResults.invalid("INVALID_SEARCH_PATH",
                        "Omit path for Workspace search, or use a historical Tool Result path");
            }
            if (artifactReader == null) return ToolResult.error(ToolStatus.INTERNAL_ERROR, "ARTIFACT_READER_UNAVAILABLE",
                    "Historical resource reading is not configured", false);

            if (SESSION_RESULTS_PATH.equals(path.asText())) {
                return artifactReader.searchSession(execution, arguments);
            }
            return artifactReader.search(execution, path.asText(), arguments);
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
