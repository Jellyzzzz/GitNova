package com.gitnova.service.agent.model;

import com.gitnova.dto.ToolCall;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral message used by {@link ModelGateway}.
 *
 * <p>Tool results use {@link ModelRole#TOOL} and must keep the provider call id that
 * identifies the preceding assistant tool call.</p>
 */
public record ModelMessage(
        ModelRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {
    public ModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);

        switch (role) {
            case SYSTEM, USER -> {
                requireText(content, role);
                rejectToolCalls(role, toolCalls);
                rejectToolCallId(role, toolCallId);
            }
            case ASSISTANT -> {
                if (!hasText(content) && toolCalls.isEmpty()) {
                    throw new IllegalArgumentException(
                            "ASSISTANT message must contain text or at least one tool call"
                    );
                }
                rejectToolCallId(role, toolCallId);
            }
            case TOOL -> {
                requireText(content, role);
                rejectToolCalls(role, toolCalls);
                requireNonBlank(toolCallId, "toolCallId");
            }
        }
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireText(String value, ModelRole role) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(role + " message content must not be blank");
        }
    }

    private static void rejectToolCalls(ModelRole role, List<ToolCall> toolCalls) {
        if (!toolCalls.isEmpty()) {
            throw new IllegalArgumentException(role + " message cannot contain tool calls");
        }
    }

    private static void rejectToolCallId(ModelRole role, String toolCallId) {
        if (toolCallId != null) {
            throw new IllegalArgumentException(role + " message cannot contain toolCallId");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
