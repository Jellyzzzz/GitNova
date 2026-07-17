package com.gitnova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM API 非流式响应 — 从 DeepSeek API 返回 JSON 解析出的结果
 *
 * stopReason 取值：
 * - "tool_use" : LLM 决定调用工具 → toolCalls 有值
 * - "stop"     : LLM 正常结束 → text 有值
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {

    /** 停止原因："tool_use" 或 "stop" */
    private String stopReason;

    /** stopReason=stop 时的纯文本回复 */
    private String text;

    /** stopReason=tool_use 时的工具调用列表 */
    private List<ToolCall> toolCalls;

    /** 是否包含工具调用 */
    public boolean hasToolCalls() {
        return "tool_use".equals(stopReason) && toolCalls != null && !toolCalls.isEmpty();
    }
}
