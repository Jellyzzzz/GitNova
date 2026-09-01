package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.tool.ToolSetSnapFactory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolSetSnapFactoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSetSnapFactory factory = new ToolSetSnapFactory(
            new CanonicalJsonCodec(objectMapper)
    );

    @Test
    void shouldCreateTheSameSnapshotRegardlessOfDefinitionOrder() {
        ToolDefinition read = definition("readFile", "Read a file", simpleSchema("path"));
        ToolDefinition write = definition("writeFile", "Write a file", simpleSchema("content"));

        ToolSetSnap first = factory.create(List.of(write, read));
        ToolSetSnap second = factory.create(List.of(read, write));

        assertEquals(first, second);
        assertEquals(1, first.schemaVersion());
        assertEquals(List.of("readFile", "writeFile"), first.enabledDefinitionNames());
        assertEquals(64, first.definitionDigest().length());
    }

    @Test
    void shouldChangeDigestWhenInputSchemaChanges() {
        ToolDefinition first = definition("readFile", "Read a file", simpleSchema("path"));
        ToolDefinition second = definition("readFile", "Read a file", simpleSchema("filePath"));

        assertNotEquals(
                factory.create(List.of(first)).definitionDigest(),
                factory.create(List.of(second)).definitionDigest()
        );
    }

    @Test
    void shouldChangeDigestWhenDescriptionChanges() {
        ObjectNode schema = simpleSchema("path");
        ToolDefinition first = definition("readFile", "Read a file", schema);
        ToolDefinition second = definition("readFile", "Read repository text", schema);

        assertNotEquals(
                factory.create(List.of(first)).definitionDigest(),
                factory.create(List.of(second)).definitionDigest()
        );
    }

    @Test
    void shouldRejectDuplicateToolNames() {
        ToolDefinition first = definition("readFile", "Read a file", simpleSchema("path"));
        ToolDefinition duplicate = definition(
                "readFile",
                "Read another file",
                simpleSchema("filePath")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(List.of(first, duplicate))
        );
    }

    @Test
    void shouldIgnoreJsonObjectFieldInsertionOrder() {
        ObjectNode first = objectMapper.createObjectNode();
        first.put("type", "object");
        ObjectNode firstProperties = first.putObject("properties");
        firstProperties.putObject("path").put("type", "string");
        firstProperties.putObject("content").put("type", "string");

        ObjectNode second = objectMapper.createObjectNode();
        ObjectNode secondProperties = second.putObject("properties");
        secondProperties.putObject("content").put("type", "string");
        secondProperties.putObject("path").put("type", "string");
        second.put("type", "object");

        assertEquals(
                factory.create(List.of(definition("writeFile", "Write a file", first)))
                        .definitionDigest(),
                factory.create(List.of(definition("writeFile", "Write a file", second)))
                        .definitionDigest()
        );
    }

    @Test
    void shouldRejectMissingDefinitions() {
        ToolDefinition read = definition("readFile", "Read a file", simpleSchema("path"));

        assertThrows(NullPointerException.class, () -> factory.create(null));
        assertThrows(IllegalArgumentException.class, () -> factory.create(List.of()));
        assertThrows(
                NullPointerException.class,
                () -> factory.create(Arrays.asList(read, null))
        );
    }

    private ToolDefinition definition(
            String name,
            String description,
            ObjectNode inputSchema
    ) {
        return new ToolDefinition(name, description, inputSchema);
    }

    private ObjectNode simpleSchema(String propertyName) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject(propertyName)
                .put("type", "string");
        return schema;
    }
}
