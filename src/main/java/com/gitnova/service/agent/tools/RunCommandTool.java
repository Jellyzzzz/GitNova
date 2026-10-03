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
                .put("minimum", 0)
                .put("description", "Use the latest observed Workspace generation, not a guessed increment. "
                        + "A stale value returns CONFLICT without executing the command.");
        ObjectNode argv = properties.putObject("argv");
        argv.put("type", "array");
        argv.put("minItems", 1);
        argv.put("maxItems", MAX_COMMAND_ARG_COUNT);
        argv.put("description", "Executable followed by arguments; no implicit shell expansion. "
                + "Use [\"sh\", \"-c\", \"...\"] for pipelines, redirects or multiple commands. "
                + "Each argument must be non-blank and at most " + MAX_COMMAND_ARG_BYTES
                + " UTF-8 bytes; all arguments together at most " + MAX_COMMAND_TOTAL_ARG_BYTES
                + " UTF-8 bytes.");
        argv.putObject("items")
                .put("type", "string")
                .put("minLength", 1)
                .put("pattern", "\\S")
                .put("maxLength", MAX_COMMAND_ARG_BYTES);
        properties.putObject("workingDirectory")
                .put("type", "string")
                .put("maxLength", MAX_PATH_CHARS)
                .put("description", "Existing Workspace-relative directory; use '.' for the root. "
                        + "No absolute host paths or '..' traversal. A previous call's cd is not retained.");
        properties.putObject("timeoutSeconds")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_COMMAND_TIMEOUT_SECONDS)
                .put("description", "Execution timeout for this call in seconds; container cleanup may take "
                        + "additional time. Timeout does not undo Workspace writes.");
        properties.putObject("purpose")
                .put("type", "string")
                .put("maxLength", MAX_COMMAND_PURPOSE_CHARS)
                .put("description", "Short explanation of why this command is needed; not executed as code.");
        schema.putArray("required")
                .add("expectedGeneration")
                .add("argv")
                .add("workingDirectory")
                .add("timeoutSeconds")
                .add("purpose");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "runCommand",
                """
                Executes one foreground argv command in a fresh network-disabled Docker container.
                Only the mounted Workspace persists across calls; /tmp, shell state, environment changes
                and background processes do not. Create, compile and run a temporary probe in the same call.
                Do not split a /tmp-based workflow across calls.

                Workspace changes are not rolled back on failure or timeout. generationBefore/generationAfter
                describe observed Workspace versions, not command counts.
                Tool SUCCESS means an execution result is available, not that tests or the task passed.
                Inspect payload.status, exitCode, stdout and stderr; TIMED_OUT is not validation success.
                In shell scripts, preserve the tested command's exit status; a final echo/grep must not hide failure.

                stdout/stderr capture is bounded. stdoutTruncated/stderrTruncated mean text was not fully
                captured and cannot be recovered from that result. Preview omission is different: read captured
                content using supplied artifact:// references via readFile/searchText. Never invent an Artifact path.
                Reuse captured output for unchanged code and the same validation scope. Do not rerun tests solely
                to recount or reformat output. Repeat execution when new evidence is needed: relevant code/environment
                changes, missing coverage, insufficient captured evidence, reliability checks or an explicit user request.
                """,
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        ToolResult invalid = validate(execution, arguments);
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

    private ToolResult validate(ToolExecutionContext execution, JsonNode arguments) {
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
        for (int index = 0; index < arguments.path("argv").size(); index++) {
            JsonNode argument = arguments.path("argv").get(index);
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
                return WorkspaceToolResults.invalid(execution, "COMMAND_ARGUMENT_TOO_LARGE", "/argv/" + index,
                        "argv[" + index + "] has " + argumentBytes + " UTF-8 bytes; maximum is " + MAX_COMMAND_ARG_BYTES,
                        argumentBytes, MAX_COMMAND_ARG_BYTES);
            }
            totalArgumentBytes += argumentBytes;
        }
        if (totalArgumentBytes > MAX_COMMAND_TOTAL_ARG_BYTES) {
            return WorkspaceToolResults.invalid(execution, "COMMAND_ARGUMENTS_TOO_LARGE", "/argv",
                    "argv has " + totalArgumentBytes + " UTF-8 bytes; total maximum is " + MAX_COMMAND_TOTAL_ARG_BYTES,
                    totalArgumentBytes, MAX_COMMAND_TOTAL_ARG_BYTES);
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
