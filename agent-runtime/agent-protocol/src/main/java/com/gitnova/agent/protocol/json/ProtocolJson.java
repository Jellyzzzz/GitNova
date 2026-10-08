package com.gitnova.agent.protocol.json;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gitnova.agent.protocol.command.AgentCommand;
import com.gitnova.agent.protocol.command.CommandType;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class ProtocolJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private ProtocolJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }

    public static byte[] canonicalBytes(Object value) throws IOException {
        Objects.requireNonNull(value, "value must not be null");
        JsonNode sourceTree;
        try {
            sourceTree = MAPPER.valueToTree(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Could not convert value to protocol JSON", exception);
        }
        JsonNode sortedTree = sortObjectField(sourceTree);
        return MAPPER.writeValueAsBytes(sortedTree);
    }

    public static String sha256(Object value) throws IOException {
        byte[] jsonBytes = canonicalBytes(value);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = sha256.digest(jsonBytes);
            return HexFormat.of().formatHex(digestBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public static AgentCommand readCommand(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("command body must not be empty");
        }
        JsonNode commandNode = MAPPER.readTree(body);
        if (commandNode == null || !commandNode.isObject()) {
            throw new IOException("command body must be a JSON object");
        }

        // 先检查信封的键；允许显式null的字段，也不能省略这个键。
        List<String> commandFields = List.of("schemaVersion", "commandId", "sessionId",
                "worklineId", "runnerEpoch", "type", "taskId", "attemptId", "payload");
        Iterator<String> fields = commandNode.fieldNames();
        while (fields.hasNext()) {
            String fieldName = fields.next();
            if (!commandFields.contains(fieldName)) {
                throw new IOException("Unknown command field: " + fieldName);
            }
        }
        for (String fieldName : commandFields) {
            if (!commandNode.has(fieldName)) {
                throw new IOException(fieldName + " is required");
            }
        }

        // 检查JSON类型后再取值，避免把字符串、浮点数强转成整数。
        JsonNode schemaNode = commandNode.get("schemaVersion");
        if (!schemaNode.isIntegralNumber() || !schemaNode.canConvertToInt()) {
            throw new IOException("schemaVersion must be a 32-bit integer");
        }
        int schemaVersion = schemaNode.intValue();

        JsonNode epochNode = commandNode.get("runnerEpoch");
        if (!epochNode.isIntegralNumber() || !epochNode.canConvertToLong()) {
            throw new IOException("runnerEpoch must be a 64-bit integer");
        }
        long runnerEpoch = epochNode.longValue();

        for (String fieldName : List.of("commandId", "sessionId", "worklineId", "type")) {
            if (!commandNode.get(fieldName).isTextual()) {
                throw new IOException(fieldName + " must be a string");
            }
        }
        String commandId = commandNode.get("commandId").textValue();
        String sessionId = commandNode.get("sessionId").textValue();
        String worklineId = commandNode.get("worklineId").textValue();
        String typeName = commandNode.get("type").textValue();
        CommandType type;
        try {
            type = CommandType.valueOf(typeName);
        } catch (IllegalArgumentException exception) {
            throw new IOException("type must be a supported CommandType", exception);
        }

        for (String fieldName : List.of("taskId", "attemptId")) {
            JsonNode fieldNode = commandNode.get(fieldName);
            if (!fieldNode.isNull() && !fieldNode.isTextual()) {
                throw new IOException(fieldName + " must be a string or null");
            }
        }
        JsonNode taskNode = commandNode.get("taskId");
        JsonNode attemptNode = commandNode.get("attemptId");
        String taskId = taskNode.isNull() ? null : taskNode.textValue();
        String attemptId = attemptNode.isNull() ? null : attemptNode.textValue();

        JsonNode payloadNode = commandNode.get("payload");
        if (!payloadNode.isObject()) {
            throw new IOException("payload must be a JSON object");
        }
        List<String> payloadFields = switch (type) {
            case INITIALIZE -> List.of("bootstrapId", "bootstrapSha256", "baseCommit",
                    "publishedHead", "runtimeConfigDigest");
            case SUBMIT_TASK -> List.of("message", "expectedPublishedHead", "deadlineAt",
                    "runtimeConfigDigest", "worklineStatus");
            case STEER_TASK -> List.of("message");
            case CANCEL_TASK -> List.of("reason");
            case PREVIEW_CHANGES -> List.of("expectedPublishedHead");
            case CHECKPOINT_SESSION -> List.of("purpose");
            case ACK_CHECKPOINT -> List.of("exportId", "archiveId", "bundleSha256", "coveredThrough");
            case STOP_WORKER -> List.of("confirmedArchiveId");
        };

        Iterator<String> payloadNames = payloadNode.fieldNames();
        while (payloadNames.hasNext()) {
            String fieldName = payloadNames.next();
            if (!payloadFields.contains(fieldName)) {
                throw new IOException("Unknown payload field: " + fieldName);
            }
        }
        for (String fieldName : payloadFields) {
            JsonNode fieldNode = payloadNode.get(fieldName);
            if (fieldNode == null) {
                throw new IOException("payload." + fieldName + " is required");
            }
            if (fieldName.equals("coveredThrough")) {
                if (!fieldNode.isIntegralNumber() || !fieldNode.canConvertToLong()) {
                    throw new IOException("payload.coveredThrough must be a 64-bit integer");
                }
            } else if (!fieldNode.isTextual()) {
                throw new IOException("payload." + fieldName + " must be a string");
            }
        }

        AgentCommand.Payload payload = switch (type) {
            case INITIALIZE -> MAPPER.treeToValue(payloadNode, AgentCommand.Initialize.class);
            case SUBMIT_TASK -> MAPPER.treeToValue(payloadNode, AgentCommand.Submit.class);
            case STEER_TASK -> MAPPER.treeToValue(payloadNode, AgentCommand.Steer.class);
            case CANCEL_TASK -> MAPPER.treeToValue(payloadNode, AgentCommand.Cancel.class);
            case PREVIEW_CHANGES -> MAPPER.treeToValue(payloadNode, AgentCommand.Preview.class);
            case CHECKPOINT_SESSION -> MAPPER.treeToValue(payloadNode, AgentCommand.Checkpoint.class);
            case ACK_CHECKPOINT -> MAPPER.treeToValue(payloadNode, AgentCommand.CheckpointAck.class);
            case STOP_WORKER -> MAPPER.treeToValue(payloadNode, AgentCommand.StopWorker.class);
        };
        return new AgentCommand(schemaVersion, commandId, sessionId, worklineId,
                runnerEpoch, type, taskId, attemptId, payload);
    }

    private static JsonNode sortObjectField(JsonNode sourceNode) {
        if (sourceNode.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            Iterator<String> fields = sourceNode.fieldNames();
            while (fields.hasNext()) {
                fieldNames.add(fields.next());
            }
            fieldNames.sort((nameA, nameB) -> {
                byte[] bytesA = nameA.getBytes(UTF_8);
                byte[] bytesB = nameB.getBytes(UTF_8);
                return Arrays.compareUnsigned(bytesA, bytesB);
            });
            ObjectNode sortedNode = MAPPER.createObjectNode();
            for (String fieldName : fieldNames) {
                JsonNode child = sourceNode.get(fieldName);
                JsonNode sortedChild = sortObjectField(child);
                sortedNode.set(fieldName, sortedChild);
            }
            return sortedNode;
        }
        if (sourceNode.isArray()) {
            ArrayNode copiedArray = MAPPER.createArrayNode();
            for (JsonNode element : sourceNode) {
                JsonNode sortedElement = sortObjectField(element);
                copiedArray.add(sortedElement);
            }
            return copiedArray;
        }
        return sourceNode.deepCopy();
    }
}
