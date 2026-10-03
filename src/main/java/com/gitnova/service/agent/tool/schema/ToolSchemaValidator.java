package com.gitnova.service.agent.tool.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public final class ToolSchemaValidator {
    private static final int MAX_REPORTED_VIOLATIONS = 16;
    private static final int MAX_REPORTED_PATH_CHARS = 256;

    public record Violation(String path, String code, String message) {
        public Violation {
            if (path == null || path.isBlank()) path = "/";
            if (path.length() > MAX_REPORTED_PATH_CHARS) {
                path = path.substring(0, MAX_REPORTED_PATH_CHARS - 3) + "...";
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("violation code must not be blank");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("violation message must not be blank");
            }
            if (message.length() > 512) message = message.substring(0, 509) + "...";
        }
    }

    private ToolSchemaValidator() {}

    public static List<String> validate(ToolDefinition definition, JsonNode arguments){
        return validateDetailed(definition, arguments).stream()
                .map(Violation::message)
                .toList();
    }

    public static List<Violation> validateDetailed(ToolDefinition definition, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return List.of(new Violation(
                    "/",
                    "OBJECT_REQUIRED",
                    "arguments must be a JSON object"
            ));
        }
        List<Violation> errors = new ArrayList<>();
        validateNode(definition.inputSchema(), arguments, "", errors);
        return List.copyOf(errors);
    }

    /** Walk the declared schema, retaining array indices and object keys in JSON Pointer paths. */
    private static void validateNode(JsonNode schema, JsonNode value, String path, List<Violation> errors) {
        if (errors.size() >= MAX_REPORTED_VIOLATIONS) return;
        if (schema.isBoolean()) {
            if (!schema.booleanValue()) add(errors, new Violation(path, "VALUE_FORBIDDEN", "value is not allowed"));
            return;
        }
        JsonNode type = schema.path("type");
        if (!type.isMissingNode() && !matchesType(value, type)) {
            add(errors, new Violation(path, "TYPE_MISMATCH",
                    "field '" + (path.isEmpty() ? "/" : path.substring(1)) + "' must be " + describeType(type)));
            return;
        }
        if (schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode candidate : schema.path("enum")) {
                if (candidate.equals(value)) { matched = true; break; }
            }
            if (!matched) add(errors, new Violation(path, "ENUM_MISMATCH",
                    "value must be one of " + schema.path("enum")));
        }
        if (value.isObject()) {
            for (JsonNode field : schema.path("required")) {
                String name = field.asText();
                JsonNode child = value.get(name);
                JsonNode childType = schema.path("properties").path(name).path("type");
                if (child == null || (child.isNull() && !childType.isMissingNode() && !matchesType(child, childType))) {
                    add(errors, new Violation(path + pointer(name), "REQUIRED", "missing required field: " + name));
                }
            }
            Iterator<String> names = value.fieldNames();
            while (names.hasNext() && errors.size() < MAX_REPORTED_VIOLATIONS) {
                String name = names.next();
                JsonNode property = schema.path("properties").get(name);
                if (property != null) {
                    validateNode(property, value.get(name), path + pointer(name), errors);
                } else if (schema.path("additionalProperties").isObject()) {
                    validateNode(schema.path("additionalProperties"), value.get(name), path + pointer(name), errors);
                } else if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").booleanValue()) {
                    add(errors, new Violation(path + pointer(name), "UNKNOWN_FIELD", "unknown field: " + name));
                }
            }
        } else if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
                add(errors, new Violation(path, "MIN_ITEMS", "array requires at least " + schema.path("minItems") + " items; received " + value.size()));
            }
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
                add(errors, new Violation(path, "MAX_ITEMS", "array allows at most " + schema.path("maxItems") + " items; received " + value.size()));
            }
            HashSet<JsonNode> seen = schema.path("uniqueItems").asBoolean(false) ? new HashSet<>() : null;
            for (int i = 0; i < value.size() && errors.size() < MAX_REPORTED_VIOLATIONS; i++) {
                JsonNode item = value.get(i);
                String itemPath = path + "/" + i;
                if (seen != null && !seen.add(item)) {
                    add(errors, new Violation(itemPath, "DUPLICATE_ITEM", "array items must be unique"));
                }
                if (schema.has("items")) validateNode(schema.path("items"), item, itemPath, errors);
            }
        } else if (value.isTextual()) {
            String text = value.textValue();
            // JSON Schema length counts Unicode code points, not UTF-8 bytes or Java char units.
            int length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) {
                add(errors, new Violation(path, "MIN_LENGTH", "string requires at least " + schema.path("minLength") + " characters; received " + length));
            }
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) {
                add(errors, new Violation(path, "MAX_LENGTH", "string allows at most " + schema.path("maxLength") + " characters; received " + length));
            }
            if (schema.path("pattern").isTextual() && !Pattern.compile(schema.path("pattern").asText()).matcher(text).find()) {
                add(errors, new Violation(path, "PATTERN_MISMATCH", "string does not match its required pattern"));
            }
        } else if (value.isNumber()) {
            if (schema.path("minimum").isNumber() && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0) {
                add(errors, new Violation(path, "MINIMUM", "number must be at least " + schema.path("minimum")));
            }
            if (schema.path("maximum").isNumber() && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0) {
                add(errors, new Violation(path, "MAXIMUM", "number must be at most " + schema.path("maximum")));
            }
        }
        if (schema.path("anyOf").isArray()) {
            boolean matched = false;
            List<Violation> closest = null;
            for (JsonNode alternative : schema.path("anyOf")) {
                List<Violation> branchErrors = new ArrayList<>();
                validateNode(alternative, value, path, branchErrors);
                if (branchErrors.isEmpty()) {
                    matched = true;
                    break;
                }
                if (closest == null || branchErrors.size() < closest.size()) closest = branchErrors;
            }
            if (!matched) {
                add(errors, new Violation(path, "ANY_OF_MISMATCH", "arguments must match one of the declared anyOf forms"));
                // A generic anyOf failure is not actionable; expose the nearest form's field errors too.
                if (closest != null) {
                    for (Violation error : closest) if (!errors.contains(error)) add(errors, error);
                }
            }
        }
    }

    private static void add(List<Violation> errors, Violation violation) {
        if (errors.size() < MAX_REPORTED_VIOLATIONS) {
            errors.add(violation);
        }
    }

    private static String pointer(String fieldName) {
        return "/" + fieldName.replace("~", "~0").replace("/", "~1");
    }
    private static boolean matchesType(JsonNode value, JsonNode typeNode) {
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if (matchesType(value, candidate.asText())) {
                    return true;
                }
            }
            return false;
        }
        return matchesType(value, typeNode.asText("string"));
    }
    private static String describeType(JsonNode typeNode) {
        if (!typeNode.isArray()) {
            return typeNode.asText("string");
        }
        List<String> types = new ArrayList<>();
        typeNode.forEach(node -> types.add(node.asText()));
        return String.join(" or ", types);
    }
    private static boolean matchesType(JsonNode value,String expectedType){
        return switch (expectedType) {
            case "string"  -> value.isTextual();
            case "integer" -> value.isIntegralNumber();   // 1, 42（不含 3.5）
            case "number"  -> value.isNumber();            // 1, 3.5, 1e5
            case "boolean" -> value.isBoolean();
            case "array"   -> value.isArray();
            case "object"  -> value.isObject();
            case "null"    -> value.isNull();
            default        -> true;                        // 未知类型声明，不检查
        };
    }
}
