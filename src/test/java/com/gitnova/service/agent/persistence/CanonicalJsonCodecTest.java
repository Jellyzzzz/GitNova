package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalJsonCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonCodec codec = new CanonicalJsonCodec(objectMapper);

    @Test
    void shouldSortNestedObjectsWithoutReorderingArraysOrChangingInput() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {"z":[{"b":2,"a":1},3,2,1,null],"a":{"y":false,"x":true}}
                """);
        String originalJson = source.toString();

        CanonicalJsonCodec.EncodedJson encoded = codec.encode(source);

        assertEquals("{\"a\":{\"x\":true,\"y\":false},\"z\":[{\"a\":1,\"b\":2},3,2,1,null]}", encoded.json());
        assertEquals(originalJson, source.toString());
    }

    @Test
    void shouldKeepTheHistoricalJavaStringKeyOrder() throws Exception {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("\uE000", 1);
        source.put("\uD800\uDC00", 2);

        CanonicalJsonCodec.EncodedJson encoded = codec.encode(source);
        JsonNode decoded = objectMapper.readTree(encoded.json());
        Iterator<String> fieldNames = decoded.fieldNames();

        // Java UTF-16顺序与新协议的UTF-8字节序不同，旧摘要不能跟着改。
        assertEquals("\uD800\uDC00", fieldNames.next());
        assertEquals("\uE000", fieldNames.next());
    }

    @Test
    void shouldPreserveMessageWhitespaceUnicodeAndExplicitNull() throws Exception {
        String message = "  中文\n\"quoted\"  ";
        ObjectNode source = objectMapper.createObjectNode();
        source.put("message", message);
        source.putNull("optional");

        CanonicalJsonCodec.EncodedJson encoded = codec.encode(source);
        JsonNode decoded = objectMapper.readTree(encoded.json());

        assertEquals(message, decoded.get("message").textValue());
        assertTrue(decoded.get("optional").isNull());
    }

    @Test
    void shouldGiveJavaValuesAndJsonTreesTheSameEncoding() throws Exception {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("z", 2);
        source.put("a", 1);
        JsonNode tree = objectMapper.readTree("{\"a\":1,\"z\":2}");

        CanonicalJsonCodec.EncodedJson fromValue = codec.encodeValue(source);
        CanonicalJsonCodec.EncodedJson fromTree = codec.encode(tree);

        assertEquals(fromTree, fromValue);
        assertEquals("{\"a\":1,\"z\":2}", fromValue.json());
        // 固定JSON字节的SHA-256，由独立的shasum命令核对。
        assertEquals("99168216144c7fed5d4c54916cf98d9c66096280c04a499822a99b6658bd177a", fromValue.digest());
    }

    @Test
    void shouldContinueRejectingNullInput() {
        NullPointerException treeError = assertThrows(NullPointerException.class, () -> codec.encode(null));
        NullPointerException valueError = assertThrows(NullPointerException.class, () -> codec.encodeValue(null));

        assertEquals("value must not be null", treeError.getMessage());
        assertNull(valueError.getMessage());
    }
}
