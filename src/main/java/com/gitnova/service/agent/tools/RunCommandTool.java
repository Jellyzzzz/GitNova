package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_COMMAND_ARG_BYTES;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_COMMAND_ARG_COUNT;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_COMMAND_PURPOSE_CHARS;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_COMMAND_TIMEOUT_SECONDS;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_COMMAND_TOTAL_ARG_BYTES;
import static com.gitnova.service.agent.workspace.WorkspaceGateway.MAX_PATH_CHARS;

/** Executes one argv command through the configured isolated Workspace command executor. */
public final class RunCommandTool implements AgentTool {

    private final WorkspaceGateway workspaceGateway;
    private final ObjectMapper objectMapper;

    public RunCommandTool(WorkspaceGateway workspaceGateway, ObjectMapper objectMapper) {
        this.workspaceGateway = Objects.requireNonNull(workspaceGateway, "workspaceGateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectedGeneration")
                .put("type", "integer")
                .put("minimum", 0);
        ObjectNode argv = properties.putObject("argv");
        argv.put("type", "array");
        argv.put("minItems", 1);
        argv.put("maxItems", MAX_COMMAND_ARG_COUNT);
        argv.putObject("items")
                .put("type", "string")
                .put("maxLength", MAX_COMMAND_ARG_BYTES);
        properties.putObject("workingDirectory")
                .put("type", "string")
                .put("maxLength", MAX_PATH_CHARS);
        properties.putObject("timeoutSeconds")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_COMMAND_TIMEOUT_SECONDS);
        properties.putObject("purpose")
                .put("type", "string")
                .put("maxLength", MAX_COMMAND_PURPOSE_CHARS);
        schema.putArray("required")
                .add("expectedGeneration")
                .add("argv")
                .add("workingDirectory")
                .add("timeoutSeconds")
                .add("purpose");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "runCommand",
                "Runs one argv command inside the isolated current Workspace",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        ToolResult invalid = validate(arguments);
        if (invalid != null) {
            return invalid;
        }

        List<String> argv = new ArrayList<>();
        arguments.path("argv").forEach(value -> argv.add(value.asText()));
        WorkspaceGateway.CommandRequest request;
        try {
            request = new WorkspaceGateway.CommandRequest(
                    arguments.path("expectedGeneration").longValue(),
                    argv,
                    arguments.path("workingDirectory").asText(),
                    arguments.path("timeoutSeconds").intValue(),
                    arguments.path("purpose").asText()
            );
        } catch (IllegalArgumentException exception) {
            return WorkspaceToolResults.invalid(
                    "INVALID_COMMAND_ARGUMENTS",
                    exception.getMessage()
            );
        }

        try {
            WorkspaceGateway.CommandResult result = workspaceGateway.runCommand(
                    execution.requireWorkspaceId(),
                    execution.requireExecutionPermit(),
                    request
            );
            JsonNode payload = objectMapper.valueToTree(result);
            return switch (result.status()) {
                case COMPLETED, TIMED_OUT -> ToolResult.success(
                        payload,
                        result.stdoutTruncated() || result.stderrTruncated()
                );
                case CONFLICT -> ToolResult.error(
                        ToolStatus.CONFLICT,
                        payload,
                        result.errorCode(),
                        result.message(),
                        false
                );
                case EXECUTION_FAILED -> ToolResult.error(
                        ToolStatus.INTERNAL_ERROR,
                        payload,
                        result.errorCode(),
                        result.message(),
                        false
                );
            };
        } catch (IllegalStateException exception) {
            return WorkspaceToolResults.missingContext();
        } catch (WorkspaceOperationException exception) {
            return WorkspaceToolResults.error(exception);
        }
    }

    @Override
    public ToolAccessMode accessMode() {
        return ToolAccessMode.WORKSPACE_WRITE;
    }

    @Override
    public Set<AgentCapability> requiredCapabilities() {
        return Set.of(
                AgentCapability.WORKSPACE_MUTATION,
                AgentCapability.COMMAND_EXECUTE
        );
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }

    private ToolResult validate(JsonNode arguments) {
        if (!arguments.path("expectedGeneration").isIntegralNumber()
                || !arguments.path("expectedGeneration").canConvertToLong()
                || arguments.path("expectedGeneration").longValue() < 0) {
            return WorkspaceToolResults.invalid(
                    "INVALID_EXPECTED_GENERATION",
                    "expectedGeneration must be a non-negative integer"
            );
        }
        if (!arguments.path("argv").isArray()
                || arguments.path("argv").isEmpty()
                || arguments.path("argv").size() > MAX_COMMAND_ARG_COUNT) {
            return WorkspaceToolResults.invalid("INVALID_COMMAND_ARGV", "argv must not be empty");
        }
        long totalArgumentBytes = 0;
        for (JsonNode argument : arguments.path("argv")) {
            if (!argument.isTextual() || argument.asText().isBlank()) {
                return WorkspaceToolResults.invalid(
                        "INVALID_COMMAND_ARGV",
                        "argv must contain only non-blank strings"
                );
            }
            int argumentBytes = argument.asText()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length;
            if (argumentBytes > MAX_COMMAND_ARG_BYTES) {
                return WorkspaceToolResults.invalid(
                        "COMMAND_ARGUMENT_TOO_LARGE",
                        "one argv entry exceeds the size limit"
                );
            }
            totalArgumentBytes += argumentBytes;
        }
        if (totalArgumentBytes > MAX_COMMAND_TOTAL_ARG_BYTES) {
            return WorkspaceToolResults.invalid(
                    "COMMAND_ARGUMENTS_TOO_LARGE",
                    "argv exceeds the total size limit"
            );
        }
        if (!arguments.path("workingDirectory").isTextual()
                || arguments.path("workingDirectory").asText().isBlank()
                || arguments.path("workingDirectory").asText().length() > MAX_PATH_CHARS) {
            return WorkspaceToolResults.invalid(
                    "INVALID_WORKING_DIRECTORY",
                    "workingDirectory must not be blank"
            );
        }
        if (!arguments.path("timeoutSeconds").isIntegralNumber()) {
            return WorkspaceToolResults.invalid(
                    "INVALID_COMMAND_TIMEOUT",
                    "timeoutSeconds must be an integer"
            );
        }
        int timeout = arguments.path("timeoutSeconds").intValue();
        if (timeout < 1 || timeout > MAX_COMMAND_TIMEOUT_SECONDS) {
            return WorkspaceToolResults.invalid(
                    "INVALID_COMMAND_TIMEOUT",
                    "timeoutSeconds must be between 1 and " + MAX_COMMAND_TIMEOUT_SECONDS
            );
        }
        if (!arguments.path("purpose").isTextual()
                || arguments.path("purpose").asText().isBlank()
                || arguments.path("purpose").asText().length() > MAX_COMMAND_PURPOSE_CHARS) {
            return WorkspaceToolResults.invalid(
                    "INVALID_COMMAND_PURPOSE",
                    "purpose must not be blank"
            );
        }
        return null;
    }
}
