package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Stable persisted JSON encoding shared by Step and Outbox identities. */
@Component
public final class CanonicalJsonCodec {
    private final ObjectMapper objectMapper;

    public CanonicalJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public EncodedJson encodeValue(Object value) {
        Objects.requireNonNull(value);
        JsonNode sourceTree = objectMapper.valueToTree(value);
        return encode(sourceTree);
    }

    public EncodedJson encode(JsonNode sourceTree) {
        Objects.requireNonNull(sourceTree, "value must not be null");
        try {
            JsonNode sortedTree = sortObjectFields(sourceTree);
            String json = objectMapper.writeValueAsString(sortedTree);
            String digest = sha256(json);
            return new EncodedJson(json, digest);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode canonical persisted JSON", exception);
        }
    }

    public ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    /** 生成新树：只排序对象字段，数组元素保留原序，不修改输入。 */
    private JsonNode sortObjectFields(JsonNode sourceNode) {
        if (sourceNode.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            Iterator<String> fields = sourceNode.fieldNames();
            while (fields.hasNext()) {
                String fieldName = fields.next();
                fieldNames.add(fieldName);
            }

            // 保持旧持久化摘要使用的Java字符串排序规则。
            fieldNames.sort(Comparator.naturalOrder());

            ObjectNode sortedObject = objectMapper.createObjectNode();
            for (String fieldName : fieldNames) {
                JsonNode child = sourceNode.get(fieldName);
                JsonNode sortedChild = sortObjectFields(child);
                sortedObject.set(fieldName, sortedChild);
            }
            return sortedObject;
        }

        if (sourceNode.isArray()) {
            ArrayNode copiedArray = objectMapper.createArrayNode();
            for (JsonNode element : sourceNode) {
                JsonNode sortedElement = sortObjectFields(element);
                copiedArray.add(sortedElement);
            }
            return copiedArray;
        }

        // 标量节点没有子节点，到这里结束递归。
        return sourceNode.deepCopy();
    }

    private static String sha256(String json) {
        try {
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = sha256.digest(jsonBytes);
            return HexFormat.of().formatHex(digestBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public record EncodedJson(String json, String digest) {
        public EncodedJson {
            Objects.requireNonNull(json, "json must not be null");
            Objects.requireNonNull(digest, "digest must not be null");
        }
    }
}
