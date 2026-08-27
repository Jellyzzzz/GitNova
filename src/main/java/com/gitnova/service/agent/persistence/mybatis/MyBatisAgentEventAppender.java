package com.gitnova.service.agent.persistence.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.entity.agent.AgentSessionEntity;
import com.gitnova.entity.agent.AgentStepEntity;
import com.gitnova.mapper.agent.AgentSessionMapper;
import com.gitnova.mapper.agent.AgentStepMapper;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** MySQL implementation of the shared append-only event boundary. */
@Repository
public class MyBatisAgentEventAppender implements AgentEventAppender {

    private final AgentSessionMapper sessionMapper;
    private final AgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    public MyBatisAgentEventAppender(
            AgentSessionMapper sessionMapper,
            AgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper must not be null");
        this.stepMapper = Objects.requireNonNull(stepMapper, "stepMapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AppendResult append(AppendCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.runId() != null) {
            throw new UnsupportedOperationException(
                    "Run-local sequence allocation is introduced with the Run persistence stage"
            );
        }

        String payloadJson = writeStableJson(command.persistedPayload());
        String payloadDigest = sha256(payloadJson);
        String eventDigest = eventDigest(command, payloadDigest);

        AgentSessionEntity session = sessionMapper.selectForUpdate(command.sessionId());
        if (session == null) {
            throw new IllegalArgumentException("Unknown Session: " + command.sessionId());
        }
        AgentStepEntity existing = stepMapper.selectByEventId(command.eventId());
        if (existing != null) {
            if (!eventDigest.equals(existing.getEventDigest())) {
                throw new IllegalStateException(
                        "eventId is already committed with different event semantics: " + command.eventId()
                );
            }
            return new AppendResult(
                    existing.getStepId(),
                    existing.getSessionSequence(),
                    existing.getRunStepSequence(),
                    true
            );
        }
        long previousSequence = requireNonNegative(
                session.getLastSessionSequence(),
                "lastSessionSequence"
        );
        long nextSequence = Math.incrementExact(previousSequence);
        if (sessionMapper.advanceSequence(
                command.sessionId(),
                previousSequence,
                nextSequence
        ) != 1) {
            throw new IllegalStateException("Could not advance Session sequence");
        }

        AgentStepEntity step = new AgentStepEntity();
        step.setEventId(command.eventId());
        step.setEventDigest(eventDigest);
        step.setSessionId(command.sessionId());
        step.setSessionSequence(nextSequence);
        step.setTaskId(command.taskId());
        step.setRunId(command.runId());
        step.setRunStepSequence(null);
        step.setStepType(command.stepType().name());
        step.setSchemaVersion(command.schemaVersion());
        step.setPayloadJson(payloadJson);
        step.setPersistedPayloadDigest(payloadDigest);
        step.setCausationEventId(command.causationEventId());
        step.setCorrelationId(command.correlationId());
        step.setWorkspaceEpoch(command.workspaceEpoch());
        step.setWorkspaceGeneration(command.workspaceGeneration());
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (stepMapper.insert(step) != 1 || step.getStepId() == null) {
            throw new IllegalStateException("Could not append Agent Step");
        }
        return new AppendResult(step.getStepId(), nextSequence, null, false);
    }

    private String eventDigest(AppendCommand command, String payloadDigest) {
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("sessionId", command.sessionId());
        putNullable(identity, "taskId", command.taskId());
        putNullable(identity, "runId", command.runId());
        identity.put("stepType", command.stepType().name());
        identity.put("schemaVersion", command.schemaVersion());
        identity.put("payloadDigest", payloadDigest);
        putNullable(identity, "causationEventId", command.causationEventId());
        putNullable(identity, "correlationId", command.correlationId());
        putNullable(identity, "workspaceEpoch", command.workspaceEpoch());
        putNullable(identity, "workspaceGeneration", command.workspaceGeneration());
        return sha256(writeStableJson(identity));
    }

    /**
     * Stable v1 encoding for persisted Step identity. Object fields are sorted
     * recursively; array order remains semantically significant.
     */
    private String writeStableJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(sortObjectFields(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode persisted Step payload", exception);
        }
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
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static long requireNonNegative(Long value, String field) {
        if (value == null || value < 0) {
            throw new IllegalStateException(field + " must be a persisted non-negative value");
        }
        return value;
    }
}
