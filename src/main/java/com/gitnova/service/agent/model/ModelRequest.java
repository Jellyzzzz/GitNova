package com.gitnova.service.agent.model;

import com.gitnova.dto.ToolDefinition;

import java.util.List;
import java.util.Objects;

/**
 * A complete provider-neutral non-streaming model request.
 */
public record ModelRequest(
        String model,
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        Integer maxOutputTokens,
        Double temperature,
        String requestId
) {
    public ModelRequest {
        requireNonBlank(model, "model");
        requireNonBlank(requestId, "requestId");
        messages = immutableNonEmpty(messages, "messages");
        tools = immutable(tools, "tools");

        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive when present");
        }
        if (temperature != null
                && (!Double.isFinite(temperature) || temperature < 0.0d)) {
            throw new IllegalArgumentException("temperature must be finite and non-negative when present");
        }
    }

    private static <T> List<T> immutableNonEmpty(List<T> values, String field) {
        List<T> copied = immutable(values, field);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return copied;
    }

    private static <T> List<T> immutable(List<T> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null values");
            }
        }
        return List.copyOf(values);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
