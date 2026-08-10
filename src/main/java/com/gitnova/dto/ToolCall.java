package com.gitnova.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * LLM 工具调用 — LLM 决定调用工具时，response 中 tool_calls 数组的每一项
 *
 * 对应 DeepSeek API 响应 JSON 中 tool_calls[].function 的结构。
 */

public record ToolCall(String id, String name, JsonNode arguments) {
    public ToolCall {
        requireNonBlank(id, "id");
        requireNonBlank(name, "name");
        Objects.requireNonNull(arguments, "arguments must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
