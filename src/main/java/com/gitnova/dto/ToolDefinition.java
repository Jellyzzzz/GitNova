package com.gitnova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 工具定义 — 告诉 LLM"你有哪些工具可用"
 *
 * 对应 DeepSeek API 请求体中 tools[] 数组的每一项。
 * 由 ToolRegistry.getToolDefinitions() 从 AgentTool 实例生成。
 */
@Data
@AllArgsConstructor
public class ToolDefinition {

    /** 工具名 */
    private String name;

    /** 工具功能描述 */
    private String description;

    /** 参数的 JSON Schema，描述工具接受哪些参数及类型 */
    private Map<String, Object> parametersSchema;
}
