package com.gitnova.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.tools.ToolExecutionContext;

import java.util.Map;

/**
 * Agent 可调用工具的统一执行契约。
 *
 * ToolDefinition 描述工具；
 * ToolExecutionContext 提供 Harness 可信上下文；
 * JsonNode arguments 表示模型生成的不可信参数；
 * ToolResult 表示结构化执行结果。
 */
public interface AgentTool {
    /**
     * 返回工具的名称、描述和输入 Schema。
     *
     * 该定义会被 ToolRegistry 收集，
     * 并通过 ModelGateway 暴露给模型。
     */
    ToolDefinition definition();

    /**
     * 执行一次工具调用。
     *
     * @param execution Harness 创建的可信执行上下文
     * @param arguments 模型提供且需要校验的不可信 JSON 参数
     * @return 结构化工具结果
     */
    ToolResult execute(ToolExecutionContext execution, JsonNode arguments);

    /**
     * 当前工具的访问模式。
     */
    default ToolAccessMode accessMode() {
        return ToolAccessMode.READ_ONLY;
    }

    /**
     * 当前 Tool Bean 是否支持并发调用。
     */
    default boolean concurrencySafe() {
        return true;
    }
}
