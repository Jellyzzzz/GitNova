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
import com.gitnova.service.agent.workspace.PatchOperation;
import com.gitnova.service.agent.workspace.PatchOperationResult;
import com.gitnova.service.agent.workspace.PatchOperationStatus;
import com.gitnova.service.agent.workspace.PatchOperationType;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies an ordered, fail-fast batch of file mutations to the Workspace bound by the Harness.
 *
 * <p>The model controls the requested operations and expected generation, but never the
 * Workspace identity or host path. A partially applied batch is returned as state-bearing
 * {@link ToolStatus#PARTIAL_SUCCESS}; callers must not retry the original batch unchanged.</p>
 */
public final class ApplyPatchTool implements AgentTool {

    private static final int MAX_OPERATIONS = 32;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_OPERATION_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_BATCH_TEXT_BYTES = 4 * 1024 * 1024;

    private static final Set<String> OPERATION_FIELDS = Set.of(
            "type",
            "filePath",
            "patch",
            "content"
    );

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public ApplyPatchTool(
            WorkspaceGateway workspaceGateway,
            ObjectMapper objectMapper
    ) {
        this.workspaceGateway = Objects.requireNonNull(
                workspaceGateway,
                "workspaceGateway must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectedGeneration")
                .put("type", "integer")
                .put("minimum", 0)
                .put(
                        "description",
                        "Workspace generation observed before preparing this patch"
                );

        ObjectNode operations = properties.putObject("operations");
        operations.put("type", "array");
        operations.put("minItems", 1);
        operations.put("maxItems", MAX_OPERATIONS);

        ObjectNode operation = operations.putObject("items");
        operation.put("type", "object");
        ObjectNode operationProperties = operation.putObject("properties");
        operationProperties.putObject("type")
                .put("type", "string")
                .putArray("enum")
                .add("CREATE")
                .add("UPDATE")
                .add("DELETE");
        operationProperties.putObject("filePath")
                .put("type", "string")
                .put("description", "Normalized repository-relative path");
        operationProperties.putObject("patch")
                .put("type", "string")
                .put("description", "Unified diff required only for UPDATE");
        operationProperties.putObject("content")
                .put("type", "string")
                .put("description", "Complete UTF-8 content required only for CREATE");
        operation.putArray("required")
                .add("type")
                .add("filePath");
        operation.put("additionalProperties", false);

        schema.putArray("required")
                .add("expectedGeneration")
                .add("operations");
        schema.put("additionalProperties", false);

        return new ToolDefinition(
                "applyPatch",
                "Applies ordered CREATE, UPDATE, and DELETE operations to the current "
                        + "Workspace. Operations stop after the first failure; an already "
                        + "applied prefix remains visible and advances the Workspace generation.",
                schema
        );
    }

    @Override
    public ToolResult execute(
            ToolExecutionContext execution,
            JsonNode arguments
    ) {
        Objects.requireNonNull(execution, "execution must not be null");

        WorkspaceId workspaceId;
        try {
            workspaceId = execution.requireWorkspaceId();
        } catch (IllegalStateException exception) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "WORKSPACE_CONTEXT_REQUIRED",
                    "applyPatch requires a Harness-bound Workspace",
                    false
            );
        }

        WorkspaceMutationCommand command;
        try {
            command = parseCommand(arguments);
        } catch (InvalidPatchArguments exception) {
            return ToolResult.error(
                    ToolStatus.INVALID_ARGUMENT,
                    exception.errorCode(),
                    exception.getMessage(),
                    false
            );
        }

        PatchBatchResult batch = workspaceGateway.applyPatch(workspaceId, command);
        JsonNode payload = objectMapper.valueToTree(batch);

        return switch (batch.status()) {
            case SUCCESS -> ToolResult.success(payload);
            case PARTIAL_SUCCESS -> ToolResult.partialSuccess(
                    payload,
                    batch.errorCode(),
                    batch.message()
            );
            case CONFLICT -> ToolResult.error(
                    ToolStatus.CONFLICT,
                    payload,
                    batch.errorCode(),
                    batch.message(),
                    false
            );
            case FAILED -> mapFailedBatch(batch, payload);
        };
    }

    @Override
    public ToolAccessMode accessMode() {
        return ToolAccessMode.WORKSPACE_WRITE;
    }

    @Override
    public boolean concurrencySafe() {
        // The tool is stateless. WorkspaceGateway serializes mutations per Workspace.
        return true;
    }

    private WorkspaceMutationCommand parseCommand(JsonNode arguments)
            throws InvalidPatchArguments {
        if (arguments == null || !arguments.isObject()) {
            throw invalid("INVALID_PATCH_ARGUMENTS", "arguments must be a JSON object");
        }

        JsonNode generationNode = arguments.get("expectedGeneration");
        if (generationNode == null
                || !generationNode.isIntegralNumber()
                || !generationNode.canConvertToLong()
                || generationNode.longValue() < 0) {
            throw invalid(
                    "INVALID_EXPECTED_GENERATION",
                    "expectedGeneration must be a non-negative 64-bit integer"
            );
        }

        JsonNode operationsNode = arguments.get("operations");
        if (operationsNode == null || !operationsNode.isArray()) {
            throw invalid("INVALID_PATCH_OPERATIONS", "operations must be an array");
        }
        if (operationsNode.isEmpty() || operationsNode.size() > MAX_OPERATIONS) {
            throw invalid(
                    "INVALID_PATCH_OPERATION_COUNT",
                    "operations must contain between 1 and " + MAX_OPERATIONS + " items"
            );
        }

        List<PatchOperation> operations = new ArrayList<>(operationsNode.size());
        Set<String> paths = new HashSet<>();
        int totalTextBytes = 0;

        for (int index = 0; index < operationsNode.size(); index++) {
            JsonNode operationNode = operationsNode.get(index);
            if (!operationNode.isObject()) {
                throw invalid(
                        "INVALID_PATCH_OPERATION",
                        "operation at index " + index + " must be an object"
                );
            }
            rejectUnknownOperationFields(operationNode, index);

            String typeText = requiredText(operationNode, "type", index, false);
            String filePath = requiredText(operationNode, "filePath", index, false);
            if (filePath.length() > MAX_PATH_LENGTH) {
                throw invalid(
                        "WORKSPACE_PATH_TOO_LONG",
                        "filePath at index " + index + " exceeds the size limit"
                );
            }
            if (!paths.add(filePath)) {
                throw invalid(
                        "DUPLICATE_PATCH_PATH",
                        "operations must not target the same file more than once"
                );
            }

            PatchOperationType type;
            try {
                type = PatchOperationType.valueOf(typeText);
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        "INVALID_PATCH_OPERATION_TYPE",
                        "operation type must be CREATE, UPDATE, or DELETE"
                );
            }

            PatchOperation parsed;
            switch (type) {
                case CREATE -> {
                    rejectPresent(operationNode, "patch", type, index);
                    String content = requiredText(operationNode, "content", index, true);
                    totalTextBytes = addTextBytes(totalTextBytes, content, index);
                    parsed = PatchOperation.create(index, filePath, content);
                }
                case UPDATE -> {
                    rejectPresent(operationNode, "content", type, index);
                    String patch = requiredText(operationNode, "patch", index, false);
                    totalTextBytes = addTextBytes(totalTextBytes, patch, index);
                    parsed = PatchOperation.update(index, filePath, patch);
                }
                case DELETE -> {
                    rejectPresent(operationNode, "patch", type, index);
                    rejectPresent(operationNode, "content", type, index);
                    parsed = PatchOperation.delete(index, filePath);
                }
                default -> throw new IllegalStateException("Unhandled patch operation type");
            }
            operations.add(parsed);
        }

        return new WorkspaceMutationCommand(
                generationNode.longValue(),
                operations
        );
    }

    private ToolResult mapFailedBatch(PatchBatchResult batch, JsonNode payload) {
        PatchOperationResult failure = batch.operationResults()
                .stream()
                .filter(result -> result.status() == PatchOperationStatus.FAILED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "FAILED patch batch does not contain a failed operation"
                ));

        ToolStatus status = switch (failure.errorCode()) {
            case "INVALID_WORKSPACE_PATH", "UNSAFE_WORKSPACE_PATH" ->
                    ToolStatus.PERMISSION_DENIED;
            case "FILE_NOT_FOUND" -> ToolStatus.NOT_FOUND;
            case "INVALID_UNIFIED_DIFF", "FILE_NOT_UTF8_TEXT", "MIXED_LINE_ENDINGS",
                    "UNSUPPORTED_FILE_TYPE" -> ToolStatus.INVALID_ARGUMENT;
            case "FILESYSTEM_FAILURE", "ATOMIC_WRITE_UNAVAILABLE", "WORKSPACE_UNAVAILABLE" ->
                    ToolStatus.INTERNAL_ERROR;
            default -> ToolStatus.CONFLICT;
        };

        return ToolResult.error(
                status,
                payload,
                failure.errorCode(),
                failure.message(),
                false
        );
    }

    private void rejectUnknownOperationFields(JsonNode operation, int index)
            throws InvalidPatchArguments {
        Iterator<String> fields = operation.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!OPERATION_FIELDS.contains(field)) {
                throw invalid(
                        "UNKNOWN_PATCH_OPERATION_FIELD",
                        "unknown field '" + field + "' at operation index " + index
                );
            }
        }
    }

    private String requiredText(
            JsonNode operation,
            String field,
            int index,
            boolean allowEmpty
    ) throws InvalidPatchArguments {
        JsonNode value = operation.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(
                    "INVALID_PATCH_OPERATION",
                    "field '" + field + "' at operation index " + index
                            + " must be a string"
            );
        }
        String text = value.textValue();
        if (!allowEmpty && text.isBlank()) {
            throw invalid(
                    "INVALID_PATCH_OPERATION",
                    "field '" + field + "' at operation index " + index
                            + " must not be blank"
            );
        }
        return text;
    }

    private void rejectPresent(
            JsonNode operation,
            String field,
            PatchOperationType type,
            int index
    ) throws InvalidPatchArguments {
        if (operation.has(field)) {
            throw invalid(
                    "INVALID_PATCH_OPERATION",
                    type + " operation at index " + index + " must not contain " + field
            );
        }
    }

    private int addTextBytes(int total, String text, int index)
            throws InvalidPatchArguments {
        int operationBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (operationBytes > MAX_OPERATION_TEXT_BYTES) {
            throw invalid(
                    "PATCH_OPERATION_TOO_LARGE",
                    "operation text at index " + index + " exceeds the size limit"
            );
        }
        long next = (long) total + operationBytes;
        if (next > MAX_BATCH_TEXT_BYTES) {
            throw invalid(
                    "PATCH_BATCH_TOO_LARGE",
                    "combined patch content exceeds the batch size limit"
            );
        }
        return (int) next;
    }

    private InvalidPatchArguments invalid(String errorCode, String message) {
        return new InvalidPatchArguments(errorCode, message);
    }

    private static final class InvalidPatchArguments extends Exception {

        private final String errorCode;

        private InvalidPatchArguments(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        String errorCode() {
            return errorCode;
        }
    }
}
