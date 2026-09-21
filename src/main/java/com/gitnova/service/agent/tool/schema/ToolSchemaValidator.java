package com.gitnova.service.agent.tool.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public final class ToolSchemaValidator {
    private ToolSchemaValidator() {}
    public static List<String> validate(ToolDefinition definition, JsonNode arguments){
        return validate(definition.inputSchema(), arguments);
    }

    private static List<String> validate(JsonNode schema, JsonNode arguments) {
        List<String>errors=new ArrayList<>();
        if(arguments==null||!arguments.isObject()) return List.of("arguments must be a JSON object");
        for(JsonNode field:schema.path("required")){
            String name=field.asText();
            if(!arguments.has(name)||arguments.get(name).isNull()){
                errors.add("missing required field: " + name);
            }
        }
        Iterator<String>fieldNames=arguments.fieldNames();
        while(fieldNames.hasNext())
        {
            String name=fieldNames.next();
            JsonNode value=arguments.get(name);
            JsonNode propSchema=schema.path("properties").get(name);
            if (propSchema == null) {
                boolean allowAdditional =
                        schema.path("additionalProperties").asBoolean(true);
                if (!allowAdditional) {
                    errors.add("unknown field: " + name);
                }
                continue;
            }
            JsonNode typeNode = propSchema.path("type");
            if (!matchesType(value, typeNode)) {
                errors.add("field '" + name + "' must be " + describeType(typeNode));
            }
            if (value.isTextual() && propSchema.path("pattern").isTextual()
                    && !Pattern.compile(propSchema.path("pattern").asText()).matcher(value.asText()).find()) {
                errors.add("field '" + name + "' does not match its required pattern");
            }
        }
        if (schema.has("anyOf")) {
            boolean matched = false;
            for (JsonNode alternative : schema.path("anyOf")) {
                if (validate(alternative, arguments).isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) errors.add("arguments must match one of the declared anyOf forms");
        }
        return errors;
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
