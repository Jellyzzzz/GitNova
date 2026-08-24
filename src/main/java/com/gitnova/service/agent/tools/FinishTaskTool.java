package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.completion.AgentCompletionDraft;
import com.gitnova.service.agent.completion.AgentFindingDraft;
import com.gitnova.service.agent.completion.ValidationClaim;
import com.gitnova.service.agent.context.Severity;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Parses the model's universal terminal request into untrusted completion claims.
 *
 * <p>This tool deliberately performs no Workspace, database, or Git side effect. The Runtime's
 * completion inspector must recompute the current generation, canonical diff, and validation
 * evidence before accepting the draft.</p>
 */
@Component
public final class FinishTaskTool implements AgentTool {

    public static final String NAME = "finishTask";

    private static final int MAX_SUMMARY_CHARS = 16_000;
    private static final int MAX_FINDINGS = 200;
    private static final int MAX_CHANGED_FILES = 1_000;
    private static final int MAX_VALIDATIONS = 100;
    private static final int MAX_COMMAND_ARGUMENTS = 128;
    private static final int MAX_LIST_ITEMS = 200;
    private static final int MAX_TEXT_CHARS = 8_000;
    private static final ToolDefinition DEFINITION = definitionSchema();

    private final ObjectMapper objectMapper;

    public FinishTaskTool(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        Objects.requireNonNull(execution, "execution must not be null");
        try {
            AgentCompletionDraft draft = parseDraft(arguments);
            return ToolResult.success(objectMapper.valueToTree(draft));
        } catch (IllegalArgumentException exception) {
            return ToolResult.error(
                    ToolStatus.INVALID_ARGUMENT,
                    "INVALID_COMPLETION_DRAFT",
                    exception.getMessage(),
                    false
            );
        }
    }

    @Override
    public boolean terminal() {
        return true;
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }

    @Override
    public Set<AgentCapability> requiredCapabilities() {
        return Set.of();
    }

    private AgentCompletionDraft parseDraft(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }
        ensureOnlyFields(
                arguments,
                "completion draft",
                List.of(
                        "expectedGeneration", "summary", "findings",
                        "claimedChangedFiles", "claimedValidations", "risks", "followUps"
                )
        );
        JsonNode generation = arguments.path("expectedGeneration");
        if (!generation.isIntegralNumber()
                || !generation.canConvertToLong()
                || generation.longValue() < 0) {
            throw new IllegalArgumentException(
                    "expectedGeneration must be a non-negative integer"
            );
        }

        String summary = requiredText(arguments, "summary", MAX_SUMMARY_CHARS);
        List<AgentFindingDraft> findings = parseFindings(arguments.path("findings"));
        List<String> changedFiles = parseStringArray(
                arguments.path("claimedChangedFiles"),
                "claimedChangedFiles",
                MAX_CHANGED_FILES,
                AgentFindingDraft.MAX_PATH_CHARS
        );
        List<ValidationClaim> validations = parseValidations(
                arguments.path("claimedValidations")
        );
        List<String> risks = parseStringArray(
                arguments.path("risks"),
                "risks",
                MAX_LIST_ITEMS,
                MAX_TEXT_CHARS
        );
        List<String> followUps = parseStringArray(
                arguments.path("followUps"),
                "followUps",
                MAX_LIST_ITEMS,
                MAX_TEXT_CHARS
        );

        return new AgentCompletionDraft(
                generation.longValue(),
                summary,
                findings,
                changedFiles,
                validations,
                risks,
                followUps
        );
    }

    private List<AgentFindingDraft> parseFindings(JsonNode node) {
        requireArray(node, "findings", MAX_FINDINGS);
        List<AgentFindingDraft> findings = new ArrayList<>();
        for (JsonNode finding : node) {
            if (!finding.isObject()) {
                throw new IllegalArgumentException("each finding must be a JSON object");
            }
            ensureOnlyFields(
                    finding,
                    "finding",
                    List.of(
                            "filePath", "startLine", "endLine", "severity", "category",
                            "evidence", "explanation", "suggestion", "confidence"
                    )
            );
            JsonNode startLine = finding.path("startLine");
            JsonNode endLine = finding.path("endLine");
            JsonNode confidence = finding.path("confidence");
            if (!startLine.isIntegralNumber() || !startLine.canConvertToInt()
                    || !endLine.isIntegralNumber() || !endLine.canConvertToInt()) {
                throw new IllegalArgumentException("finding line numbers must be integers");
            }
            if (!confidence.isNumber()) {
                throw new IllegalArgumentException("finding confidence must be a number");
            }

            Severity severity;
            try {
                severity = Severity.valueOf(
                        requiredText(finding, "severity", 32).toUpperCase(Locale.ROOT)
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "finding severity must be info, warning, or error"
                );
            }
            findings.add(new AgentFindingDraft(
                    requiredText(finding, "filePath", AgentFindingDraft.MAX_PATH_CHARS),
                    startLine.intValue(),
                    endLine.intValue(),
                    severity,
                    requiredText(finding, "category", 256),
                    requiredText(finding, "evidence", MAX_TEXT_CHARS),
                    requiredText(finding, "explanation", MAX_TEXT_CHARS),
                    requiredText(finding, "suggestion", MAX_TEXT_CHARS),
                    confidence.doubleValue()
            ));
        }
        return List.copyOf(findings);
    }

    private List<ValidationClaim> parseValidations(JsonNode node) {
        requireArray(node, "claimedValidations", MAX_VALIDATIONS);
        List<ValidationClaim> validations = new ArrayList<>();
        for (JsonNode validation : node) {
            if (!validation.isObject()) {
                throw new IllegalArgumentException(
                        "each claimed validation must be a JSON object"
                );
            }
            ensureOnlyFields(validation, "claimed validation", List.of("argv", "result"));
            List<String> argv = parseStringArray(
                    validation.path("argv"),
                    "validation argv",
                    MAX_COMMAND_ARGUMENTS,
                    MAX_TEXT_CHARS
            );
            if (argv.isEmpty()) {
                throw new IllegalArgumentException("validation argv must not be empty");
            }
            validations.add(new ValidationClaim(
                    argv,
                    requiredText(validation, "result", MAX_TEXT_CHARS)
            ));
        }
        return List.copyOf(validations);
    }

    private List<String> parseStringArray(
            JsonNode node,
            String field,
            int maxItems,
            int maxItemChars
    ) {
        requireArray(node, field, maxItems);
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IllegalArgumentException(
                        field + " must contain non-blank strings"
                );
            }
            if (value.asText().length() > maxItemChars) {
                throw new IllegalArgumentException(field + " contains an oversized value");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private String requiredText(JsonNode object, String field, int maxChars) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        if (value.asText().length() > maxChars) {
            throw new IllegalArgumentException(field + " exceeds the maximum length");
        }
        return value.asText();
    }

    private void requireArray(JsonNode node, String field, int maxItems) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        if (node.size() > maxItems) {
            throw new IllegalArgumentException(field + " contains too many items");
        }
    }

    private void ensureOnlyFields(JsonNode object, String field, List<String> allowed) {
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(field + " contains unknown field: " + name);
            }
        });
        for (String required : allowed) {
            if (!object.has(required) || object.get(required).isNull()) {
                throw new IllegalArgumentException(
                        field + " is missing required field: " + required
                );
            }
        }
    }

    private static ToolDefinition definitionSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        integer(properties, "expectedGeneration", 0);
        string(properties, "summary", 1, MAX_SUMMARY_CHARS);

        ObjectNode findings = properties.putObject("findings");
        findings.put("type", "array");
        findings.put("maxItems", MAX_FINDINGS);
        ObjectNode finding = findings.putObject("items");
        finding.put("type", "object");
        ObjectNode findingProperties = finding.putObject("properties");
        string(findingProperties, "filePath", 1, AgentFindingDraft.MAX_PATH_CHARS);
        integer(findingProperties, "startLine", 1);
        integer(findingProperties, "endLine", 1);
        ObjectNode severity = findingProperties.putObject("severity");
        severity.put("type", "string");
        severity.putArray("enum").add("info").add("warning").add("error");
        string(findingProperties, "category", 1, 256);
        string(findingProperties, "evidence", 1, MAX_TEXT_CHARS);
        string(findingProperties, "explanation", 1, MAX_TEXT_CHARS);
        string(findingProperties, "suggestion", 1, MAX_TEXT_CHARS);
        ObjectNode confidence = findingProperties.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        finding.putArray("required")
                .add("filePath")
                .add("startLine")
                .add("endLine")
                .add("severity")
                .add("category")
                .add("evidence")
                .add("explanation")
                .add("suggestion")
                .add("confidence");
        finding.put("additionalProperties", false);

        arrayOfStrings(
                properties,
                "claimedChangedFiles",
                MAX_CHANGED_FILES,
                AgentFindingDraft.MAX_PATH_CHARS,
                true
        );

        ObjectNode validations = properties.putObject("claimedValidations");
        validations.put("type", "array");
        validations.put("maxItems", MAX_VALIDATIONS);
        ObjectNode validation = validations.putObject("items");
        validation.put("type", "object");
        ObjectNode validationProperties = validation.putObject("properties");
        arrayOfStrings(
                validationProperties,
                "argv",
                MAX_COMMAND_ARGUMENTS,
                MAX_TEXT_CHARS,
                false
        );
        string(validationProperties, "result", 1, MAX_TEXT_CHARS);
        validation.putArray("required").add("argv").add("result");
        validation.put("additionalProperties", false);

        arrayOfStrings(properties, "risks", MAX_LIST_ITEMS, MAX_TEXT_CHARS, false);
        arrayOfStrings(properties, "followUps", MAX_LIST_ITEMS, MAX_TEXT_CHARS, false);
        schema.putArray("required")
                .add("expectedGeneration")
                .add("summary")
                .add("findings")
                .add("claimedChangedFiles")
                .add("claimedValidations")
                .add("risks")
                .add("followUps");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                NAME,
                "Submits the current Agent result for authoritative server inspection",
                schema
        );
    }

    private static void string(
            ObjectNode properties,
            String name,
            int minLength,
            int maxLength
    ) {
        ObjectNode value = properties.putObject(name);
        value.put("type", "string");
        value.put("minLength", minLength);
        value.put("maxLength", maxLength);
    }

    private static void integer(ObjectNode properties, String name, long minimum) {
        ObjectNode value = properties.putObject(name);
        value.put("type", "integer");
        value.put("minimum", minimum);
    }

    private static void arrayOfStrings(
            ObjectNode properties,
            String name,
            int maxItems,
            int maxItemChars,
            boolean uniqueItems
    ) {
        ObjectNode array = properties.putObject(name);
        array.put("type", "array");
        array.put("maxItems", maxItems);
        if (uniqueItems) {
            array.put("uniqueItems", true);
        }
        ObjectNode item = array.putObject("items");
        item.put("type", "string");
        item.put("minLength", 1);
        item.put("maxLength", maxItemChars);
    }
}
