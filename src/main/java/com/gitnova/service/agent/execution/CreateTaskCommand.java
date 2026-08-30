package com.gitnova.service.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Trusted identities and immutable inputs for Task plus initial Run creation. */
public record CreateTaskCommand(
        String creationIdempotencyKey,
        String taskId,
        String initialRunId,
        String sessionId,
        long createdByActorId,
        AgentTaskRequest request,
        JsonNode executionConfig
) {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    public CreateTaskCommand {
        requireAsciiNonBlank(creationIdempotencyKey, "creationIdempotencyKey");
        requireUuid(taskId, "taskId");
        requireUuid(initialRunId, "initialRunId");
        requireUuid(sessionId, "sessionId");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(executionConfig, "executionConfig must not be null");
        if (!executionConfig.isObject()) {
            throw new IllegalArgumentException("executionConfig must be a JSON object");
        }
        if (creationIdempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("creationIdempotencyKey exceeds the length limit");
        }
        if (createdByActorId <= 0) {
            throw new IllegalArgumentException("createdByActorId must be positive");
        }
        executionConfig = executionConfig.deepCopy();
    }

    public static CreateTaskCommand prepare(
            String creationIdempotencyKey,
            String sessionId,
            long createdByActorId,
            AgentTaskRequest request,
            JsonNode executionConfig
    ) {
        return new CreateTaskCommand(
                creationIdempotencyKey,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                sessionId,
                createdByActorId,
                request,
                executionConfig
        );
    }

    public String userMessageEventId() {
        return "task:user-message:" + taskId;
    }

    public String taskCreatedEventId() {
        return "task:created:" + taskId;
    }

    public String initialRunQueuedEventId() {
        return "run:queued:" + initialRunId;
    }

    public String initialDispatchEventId() {
        return "run:dispatch:" + initialRunId + ":initial";
    }

    private static void requireUuid(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static void requireAsciiNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(field + " must be non-blank ASCII");
        }
    }
}
