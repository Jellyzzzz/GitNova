package com.gitnova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * LLM 工具调用 — LLM 决定调用工具时，response 中 tool_calls 数组的每一项
 *
 * 对应 DeepSeek API 响应 JSON 中 tool_calls[].function 的结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** 工具调用的唯一 ID（LLM 生成，用于 tool result 消息关联） */
    private String id;

    /** 工具名，如 "getDiff" */
    private String name;

    /** 工具入参，如 { "repoId": "1", "commitSha1": "abc123" } */
    private Map<String, String> params;
}
