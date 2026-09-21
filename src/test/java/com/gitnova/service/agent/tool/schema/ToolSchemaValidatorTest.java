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

    @Test
    void shouldValidateAnyOfWithTheSameRequiredTypeAndUnknownFieldRules() {
        ObjectNode schema = definition().inputSchema().deepCopy();
        ObjectNode paged = schema.deepCopy();
        ObjectNode simple = schema.deepCopy();
        simple.withObject("properties").remove("pageSize");
        simple.putArray("required").add("path");
        schema.putArray("required").add("path");
        schema.putArray("anyOf").add(paged).add(simple);
        ToolDefinition tool = new ToolDefinition("read", "read", schema);
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("path", "src/A.java");
        assertTrue(ToolSchemaValidator.validate(tool, args).isEmpty());
        args.put("pageSize", 10);
        assertTrue(ToolSchemaValidator.validate(tool, args).isEmpty());
        args.putNull("pageSize");
        assertTrue(ToolSchemaValidator.validate(tool, args).contains("arguments must match one of the declared anyOf forms"));
    }

    @Test
    void shouldUsePatternSearchAndRejectWhenNoAlternativeMatches() {
        ObjectNode schema = definition().inputSchema().deepCopy();
        ObjectNode source = schema.deepCopy();
        ((ObjectNode) source.path("properties").path("path")).put("pattern", "^src/");
        ObjectNode tests = schema.deepCopy();
        ((ObjectNode) tests.path("properties").path("path")).put("pattern", "^test/");
        schema.putArray("anyOf").add(source).add(tests);
        ToolDefinition tool = new ToolDefinition("read", "read", schema);
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("path", "test/A.java").put("pageSize", 10);
        assertTrue(ToolSchemaValidator.validate(tool, args).isEmpty());
        args.put("path", "other/A.java");
        assertEquals(List.of("arguments must match one of the declared anyOf forms"), ToolSchemaValidator.validate(tool, args));
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
