package com.gitnova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 对话消息 — 对应 OpenAI API messages[] 数组中的一项
 *
 * role 取值：
 * - "system"    : 系统提示词
 * - "user"      : 用户输入 / 工具调用结果（Observation）
 * - "assistant" : LLM 回复（可能包含 toolCalls）
 * - "tool"      : 工具执行结果反馈（需设置 toolCallId）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    private String role;
    private String content;
    private List<ToolCall> toolCalls;  // 仅 role=assistant 且调用了工具时非空
    private String toolCallId;         // 仅 role=tool 时非空，标识这是对哪次调用的回应
}
