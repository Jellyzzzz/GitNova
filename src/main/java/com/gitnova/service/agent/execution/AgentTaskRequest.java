package com.gitnova.service.agent.execution;

import java.util.Objects;

/** Immutable user request for one Agent Task. */
public record AgentTaskRequest(String message) {
    public AgentTaskRequest {
        Objects.requireNonNull(message, "message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
