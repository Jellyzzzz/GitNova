package com.gitnova.service.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** Transaction-mandatory boundary for durable integration-event publication intent. */
public interface AgentOutboxWriter {
    EnqueueResult enqueue(EnqueueCommand command);

    record EnqueueCommand(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            JsonNode payload,
            Instant availableAt
    ) {
        public EnqueueCommand {
            requireNonBlank(eventId, "eventId");
            requireNonBlank(aggregateType, "aggregateType");
            requireNonBlank(aggregateId, "aggregateId");
            requireNonBlank(eventType, "eventType");
            Objects.requireNonNull(payload, "payload must not be null");
            Objects.requireNonNull(availableAt, "availableAt must not be null");
            if (!payload.isObject()) {
                throw new IllegalArgumentException("Outbox payload must be a JSON object");
            }
            payload = payload.deepCopy();
        }
    }

    record EnqueueResult(long outboxId, boolean alreadyEnqueued) {
        public EnqueueResult {
            if (outboxId <= 0) {
                throw new IllegalArgumentException("outboxId must be positive");
            }
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
