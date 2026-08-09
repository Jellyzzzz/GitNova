package com.gitnova.service.agent.tool.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaValidatorTest {

    @Test
    void shouldAcceptValidObject() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "src/Main.java");
        arguments.put("pageSize", 20);
        arguments.put("includeContext", true);

        assertTrue(ToolSchemaValidator.validate(definition(), arguments).isEmpty());
    }

    @Test
    void shouldRejectNonObjectArguments() {
        List<String> errors = ToolSchemaValidator.validate(
                definition(),
                JsonNodeFactory.instance.arrayNode()
        );

        assertEquals(List.of("arguments must be a JSON object"), errors);
    }

    @Test
    void shouldReportMissingRequiredFieldTypeMismatchAndUnknownFieldTogether() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", 123);
        arguments.put("unknown", true);

        List<String> errors = ToolSchemaValidator.validate(definition(), arguments);

        assertEquals(
                List.of(
                        "missing required field: pageSize",
                        "field 'path' must be string",
                        "unknown field: unknown"
                ),
                errors
        );
    }

    @Test
    void shouldRejectNullRequiredField() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.putNull("path");
        arguments.put("pageSize", 10);

        assertEquals(
                List.of("missing required field: path", "field 'path' must be string"),
                ToolSchemaValidator.validate(definition(), arguments)
        );
    }

    @Test
    void shouldAcceptOptionalStringOrNullUnion() {
        ObjectNode schema = definition().inputSchema().deepCopy();
        schema.withObject("properties")
                .putObject("cursor")
                .putArray("type")
                .add("string")
                .add("null");
        ToolDefinition definition = new ToolDefinition("getDiff", "diff", schema);
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "src/Main.java");
        arguments.put("pageSize", 10);
        arguments.putNull("cursor");

        assertTrue(ToolSchemaValidator.validate(definition, arguments).isEmpty());
    }

    private ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string");
        properties.putObject("pageSize").put("type", "integer");
        properties.putObject("includeContext").put("type", "boolean");
        schema.putArray("required").add("path").add("pageSize");
        schema.put("additionalProperties", false);

        return new ToolDefinition("readFile", "Reads a changed file", schema);
    }
}
