package com.gitnova.service.agent.model;

import com.gitnova.dto.ToolCall;

import java.util.List;
import java.util.Objects;

/**
 * Normalized non-streaming model response consumed by AgentRuntime.
 */
public record ModelResponse(
        String responseId,
        String text,
        List<ToolCall> toolCalls,
        ModelUsage usage,
        ModelFinishReason finishReason
) {
    public ModelResponse {
        requireNonBlank(responseId, "responseId");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");

        if (finishReason == ModelFinishReason.TOOL_CALLS && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "TOOL_CALLS finish reason requires at least one tool call"
            );
        }
        if (finishReason != ModelFinishReason.TOOL_CALLS && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool calls require TOOL_CALLS as the finish reason"
            );
        }
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
