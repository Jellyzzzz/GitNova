package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.coding.ClaimedValidation;
import com.gitnova.service.agent.coding.CodingDraft;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Parses the model's terminal Coding request into a side-effect-free draft. */
public final class FinalizeTaskTool implements AgentTool {

    private final ObjectMapper objectMapper;

    public FinalizeTaskTool(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expectedGeneration").put("type", "integer");
        properties.putObject("summary").put("type", "string");
        arrayOfStrings(properties, "changedFiles");
        ObjectNode validations = properties.putObject("validations");
        validations.put("type", "array");
        ObjectNode validation = validations.putObject("items");
        validation.put("type", "object");
        ObjectNode validationProperties = validation.putObject("properties");
        ObjectNode argv = validationProperties.putObject("argv");
        argv.put("type", "array");
        argv.putObject("items").put("type", "string");
        validationProperties.putObject("result").put("type", "string");
        validation.putArray("required").add("argv").add("result");
        validation.put("additionalProperties", false);
        arrayOfStrings(properties, "risks");
        arrayOfStrings(properties, "followUps");
        schema.putArray("required")
                .add("expectedGeneration")
                .add("summary")
                .add("changedFiles")
                .add("validations")
                .add("risks")
                .add("followUps");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "finalizeTask",
                "Submits a CodingDraft for server verification without writing Git or the database",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        try {
            execution.requireWorkspaceId();
        } catch (IllegalStateException exception) {
            return WorkspaceToolResults.missingContext();
        }

        if (!arguments.path("expectedGeneration").isIntegralNumber()
                || !arguments.path("expectedGeneration").canConvertToLong()
                || arguments.path("expectedGeneration").longValue() < 0) {
            return invalid("expectedGeneration must be a non-negative integer");
        }
        String summary = arguments.path("summary").asText();
        if (summary.isBlank()) {
            return invalid("summary must not be blank");
        }

        List<String> changedFiles = readStringArray(arguments.path("changedFiles"));
        if (changedFiles == null || changedFiles.isEmpty()) {
            return invalid("changedFiles must contain normalized repository paths");
        }
        Set<String> uniquePaths = new HashSet<>();
        for (String path : changedFiles) {
            if (!isSafeRepositoryPath(path) || !uniquePaths.add(path)) {
                return invalid("changedFiles must contain unique normalized repository paths");
            }
        }

        List<ClaimedValidation> validations = new ArrayList<>();
        if (!arguments.path("validations").isArray()) {
            return invalid("validations must be an array");
        }
        for (JsonNode node : arguments.path("validations")) {
            if (!node.isObject()) {
                return invalid("each validation must be an object");
            }
            List<String> argv = readStringArray(node.path("argv"));
            String result = node.path("result").asText();
            if (argv == null || argv.isEmpty() || result.isBlank()) {
                return invalid("validation argv and result must not be empty");
            }
            validations.add(new ClaimedValidation(argv, result));
        }

        List<String> risks = readStringArray(arguments.path("risks"));
        List<String> followUps = readStringArray(arguments.path("followUps"));
        if (risks == null || followUps == null) {
            return invalid("risks and followUps must be string arrays");
        }

        CodingDraft draft = new CodingDraft(
                arguments.path("expectedGeneration").longValue(),
                summary,
                changedFiles,
                validations,
                risks,
                followUps
        );
        return ToolResult.success(objectMapper.valueToTree(draft));
    }

    @Override
    public boolean terminal() {
        return true;
    }

    private ToolResult invalid(String message) {
        return WorkspaceToolResults.invalid("INVALID_CODING_DRAFT", message);
    }

    private List<String> readStringArray(JsonNode node) {
        if (!node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                return null;
            }
            values.add(value.asText());
        }
        return values;
    }

    private boolean isSafeRepositoryPath(String path) {
        if (path.isBlank()
                || path.indexOf('\0') >= 0
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*")
                || path.contains("\\")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static void arrayOfStrings(ObjectNode properties, String name) {
        ObjectNode array = properties.putObject(name);
        array.put("type", "array");
        array.putObject("items").put("type", "string");
    }
}
