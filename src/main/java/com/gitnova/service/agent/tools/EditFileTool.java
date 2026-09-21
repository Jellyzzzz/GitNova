package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.PatchBatchStatus;
import com.gitnova.service.agent.workspace.PatchOperation;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact replacements in one existing file, using the existing Workspace mutation boundary. */
public final class EditFileTool implements AgentTool {
    private static final Set<String> FIELDS = Set.of("expectedGeneration", "filePath", "edits");
    private static final Set<String> EDIT_FIELDS = Set.of("oldText", "newText");

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public EditFileTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectedGeneration").put("type", "integer").put("minimum", 0)
                .put("description", "Current Workspace generation observed before preparing these edits");
        properties.putObject("filePath").put("type", "string").put("minLength", 1)
                .put("maxLength", WorkspaceGateway.MAX_PATH_CHARS)
                .put("description", "Existing UTF-8 file in the Workspace; normalized repository-relative path, not an artifact URI");
        ObjectNode edits = properties.putObject("edits");
        edits.put("type", "array").put("minItems", 1).put("maxItems", PatchOperation.MAX_EDITS);
        ObjectNode item = edits.putObject("items");
        item.put("type", "object");
        ObjectNode editProperties = item.putObject("properties");
        editProperties.putObject("oldText").put("type", "string").put("minLength", 1)
                .put("maxLength", PatchOperation.MAX_EDIT_TEXT_BYTES)
                .put("description", "Exact text copied from the current file, without displayed line numbers; must occur exactly once in the original file");
        editProperties.putObject("newText").put("type", "string")
                .put("maxLength", PatchOperation.MAX_EDIT_TEXT_BYTES)
                .put("description", "Replacement text; empty deletes the match, or preserve an anchor and add text to insert");
        item.putArray("required").add("oldText").add("newText");
        item.put("additionalProperties", false);
        schema.putArray("required").add("expectedGeneration").add("filePath").add("edits");
        schema.put("additionalProperties", false);
        return new ToolDefinition("edit",
                "Prefer edit for targeted changes to an existing file; no diff headers or line counts are needed. "
                        + "Every replacement is matched against the same original file, not earlier replacement output. "
                        + "Matches must be unique and non-overlapping; merge overlapping edits. "
                        + "All replacements are validated before one atomic file write. LF/CRLF are matched equivalently; "
                        + "other characters match exactly. Use applyPatch CREATE/DELETE for new or removed files. "
                        + "Each oldText/newText is limited to 1 MiB UTF-8, with 4 MiB combined input.", schema);
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        Objects.requireNonNull(execution, "execution must not be null");
        WorkspaceMutationCommand command;
        try {
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("arguments must be an object");
            }
            var fields = arguments.fieldNames();
            while (fields.hasNext()) {
                if (!FIELDS.contains(fields.next())) throw new IllegalArgumentException("Unknown edit argument");
            }
            JsonNode generation = arguments.path("expectedGeneration");
            if (!generation.isIntegralNumber() || !generation.canConvertToLong() || generation.longValue() < 0) {
                throw new IllegalArgumentException("expectedGeneration must be a non-negative 64-bit integer");
            }
            JsonNode path = arguments.path("filePath");
            if (!path.isTextual() || path.textValue().isBlank() || path.textValue().length() > WorkspaceGateway.MAX_PATH_CHARS) {
                throw new IllegalArgumentException("filePath must be a non-blank repository-relative path within the path limit");
            }
            JsonNode items = arguments.path("edits");
            if (!items.isArray() || items.isEmpty() || items.size() > PatchOperation.MAX_EDITS) {
                throw new IllegalArgumentException("edits must contain between 1 and " + PatchOperation.MAX_EDITS + " replacements");
            }
            List<PatchOperation.TextEdit> edits = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                JsonNode item = items.get(index);
                if (!item.isObject() || !item.path("oldText").isTextual() || !item.path("newText").isTextual()) {
                    throw new IllegalArgumentException("edits[" + index + "] must contain string oldText and newText");
                }
                var names = item.fieldNames();
                while (names.hasNext()) {
                    if (!EDIT_FIELDS.contains(names.next())) throw new IllegalArgumentException("Unknown field in edits[" + index + "]");
                }
                edits.add(new PatchOperation.TextEdit(item.get("oldText").textValue(), item.get("newText").textValue()));
            }
            command = new WorkspaceMutationCommand(generation.longValue(),
                    List.of(PatchOperation.edit(0, path.textValue(), edits)));
        } catch (IllegalArgumentException exception) {
            return ToolResult.error(ToolStatus.INVALID_ARGUMENT, "INVALID_EDIT_ARGUMENTS", exception.getMessage(), false);
        }

        // Keep execution/persistence failures outside the argument-validation catch: a write may have happened.
        PatchBatchResult batch = workspaceGateway.applyPatch(
                execution.requireWorkspaceId(), execution.requireExecutionPermit(), command);
        ObjectNode payload = objectMapper.valueToTree(batch);
        payload.put("filePath", command.operations().get(0).filePath());
        payload.put("replacementCount", batch.status() == PatchBatchStatus.SUCCESS
                ? command.operations().get(0).edits().size() : 0);
        return ApplyPatchTool.toToolResult(batch, payload);
    }

    @Override
    public ToolAccessMode accessMode() {
        return ToolAccessMode.WORKSPACE_WRITE;
    }

    @Override
    public boolean concurrencySafe() {
        return true; // Stateless tool; the Gateway serializes mutations per Workspace.
    }
}
