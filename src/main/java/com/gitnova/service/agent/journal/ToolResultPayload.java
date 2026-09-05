package com.gitnova.service.agent.journal;

import com.gitnova.service.agent.tool.ToolResult;

import java.util.Objects;

/** Immutable snapshot of one terminal Tool Call outcome. */
public record ToolResultPayload(
        String modelCallId,
        String toolCallId,
        String toolName,
        ToolResult result
) {
    public ToolResultPayload {
        requireNonBlank(modelCallId, "modelCallId");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(toolName, "toolName");
        Objects.requireNonNull(result, "result must not be null");
        result = new ToolResult(
                result.status(),
                result.payload().deepCopy(),
                result.errorCode(),
                result.message(),
                result.retryable(),
                result.truncated()
        );
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
