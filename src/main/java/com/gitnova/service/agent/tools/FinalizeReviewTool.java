package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.context.Severity;
import com.gitnova.service.agent.review.ReviewDraft;
import com.gitnova.service.agent.review.ReviewIssueDraft;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses the model's final review request into a side-effect-free draft. */
@Component
public class FinalizeReviewTool implements AgentTool {

    private static final ToolDefinition DEFINITION = definitionSchema();

    private final ObjectMapper objectMapper;

    public FinalizeReviewTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        String summary = arguments.path("summary").asText();
        if (summary.isBlank()) {
            return invalidDraft("summary must not be blank");
        }

        List<ReviewIssueDraft> issues = new ArrayList<>();
        for (JsonNode issueNode : arguments.path("issues")) {
            ToolResult validationFailure = validateIssueNode(issueNode);
            if (validationFailure != null) {
                return validationFailure;
            }
            try {
                issues.add(new ReviewIssueDraft(
                        issueNode.path("filePath").asText(),
                        issueNode.path("startLine").intValue(),
                        issueNode.path("endLine").intValue(),
                        Severity.valueOf(
                                issueNode.path("severity")
                                        .asText()
                                        .toUpperCase(Locale.ROOT)
                        ),
                        issueNode.path("category").asText(),
                        issueNode.path("evidence").asText(),
                        issueNode.path("explanation").asText(),
                        issueNode.path("suggestion").asText(),
                        issueNode.path("confidence").doubleValue()
                ));
            } catch (IllegalArgumentException exception) {
                return invalidDraft("severity must be info, warning, or error");
            }
        }

        ReviewDraft draft = new ReviewDraft(summary, List.copyOf(issues));
        return ToolResult.success(objectMapper.valueToTree(draft));
    }

    private ToolResult validateIssueNode(JsonNode issue) {
        if (!issue.isObject()) {
            return invalidDraft("each issue must be a JSON object");
        }
        String[] textFields = {
                "filePath", "severity", "category", "evidence",
                "explanation", "suggestion"
        };
        for (String field : textFields) {
            if (!issue.path(field).isTextual()
                    || issue.path(field).asText().isBlank()) {
                return invalidDraft("issue field '" + field + "' must be a non-blank string");
            }
        }
        if (!isSafeRepositoryPath(issue.path("filePath").asText())) {
            return invalidDraft("issue filePath must be a normalized repository-relative path");
        }
        if (!issue.path("startLine").isIntegralNumber()
                || !issue.path("endLine").isIntegralNumber()) {
            return invalidDraft("issue line numbers must be integers");
        }
        int startLine = issue.path("startLine").intValue();
        int endLine = issue.path("endLine").intValue();
        if (startLine < 1 || endLine < startLine) {
            return invalidDraft("issue line range is invalid");
        }
        if (!issue.path("confidence").isNumber()) {
            return invalidDraft("issue confidence must be a number");
        }
        double confidence = issue.path("confidence").doubleValue();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            return invalidDraft("issue confidence must be between 0 and 1");
        }
        return null;
    }

    private boolean isSafeRepositoryPath(String path) {
        if (path.isBlank()
                || path.length() > 4096
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

    private ToolResult invalidDraft(String message) {
        return ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                "INVALID_REVIEW_DRAFT",
                message,
                false
        );
    }

    private static ToolDefinition definitionSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode issues = properties.putObject("issues");
        issues.put("type", "array");
        ObjectNode issue = issues.putObject("items");
        issue.put("type", "object");
        ObjectNode issueProperties = issue.putObject("properties");
        issueProperties.putObject("filePath").put("type", "string");
        issueProperties.putObject("startLine").put("type", "integer");
        issueProperties.putObject("endLine").put("type", "integer");
        issueProperties.putObject("severity").put("type", "string");
        issueProperties.putObject("category").put("type", "string");
        issueProperties.putObject("evidence").put("type", "string");
        issueProperties.putObject("explanation").put("type", "string");
        issueProperties.putObject("suggestion").put("type", "string");
        issueProperties.putObject("confidence").put("type", "number");
        issue.putArray("required")
                .add("filePath")
                .add("startLine")
                .add("endLine")
                .add("severity")
                .add("category")
                .add("evidence")
                .add("explanation")
                .add("suggestion")
                .add("confidence");
        issue.put("additionalProperties", false);
        schema.putArray("required").add("summary").add("issues");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "finalizeReview",
                "Returns a structured ReviewDraft without persistence or external side effects",
                schema
        );
    }
    @Override
    public boolean terminal(){return true;}
}
