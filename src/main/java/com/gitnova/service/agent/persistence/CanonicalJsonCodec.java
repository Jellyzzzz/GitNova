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
import java.util.List;
import java.util.Objects;

/** Stable persisted JSON encoding shared by Step and Outbox identities. */
@Component
public final class CanonicalJsonCodec {
    private final ObjectMapper objectMapper;

    public CanonicalJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public EncodedJson encode(JsonNode value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            String json = objectMapper.writeValueAsString(sortObjectFields(value));
            return new EncodedJson(json, sha256(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode canonical persisted JSON", exception);
        }
    }

    public ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    private JsonNode sortObjectFields(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                sorted.set(name, sortObjectFields(value.get(name)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            value.forEach(element -> sorted.add(sortObjectFields(element)));
            return sorted;
        }
        return value.deepCopy();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
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
