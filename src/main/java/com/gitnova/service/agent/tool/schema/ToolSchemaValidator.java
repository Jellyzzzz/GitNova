package com.gitnova.service.agent.tool.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ToolSchemaValidator {
    private ToolSchemaValidator() {}
    public static List<String> validate(ToolDefinition definition, JsonNode arguments){
        List<String>errors=new ArrayList<>();
        if(arguments==null||!arguments.isObject()) return List.of("arguments must be a JSON object");
        JsonNode schema=definition.inputSchema();
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
            String expected=propSchema.path("type").asText("string");
            if(!matchesType(value,expected)){
                errors.add("field '" + name + "' must be " + expected);
            }
        }
        return errors;
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
