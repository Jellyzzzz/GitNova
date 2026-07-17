package com.gitnova.service.agent;

import java.util.Map;

/**
 * Agent 工具接口 — ReAct 范式的 Action 层
 *
 * 每个工具是一个可被 LLM 调用的函数，LLM 通过工具名和参数来执行操作。
 * 工具的 description() 和 parametersSchema() 会被注入 LLM 请求的 tools 字段，
 * 帮助 LLM 理解工具的用途和参数格式，并决定何时调用。
 */
public interface AgentTool {

    /** 工具名称（LLM 用此名称调用），如 "getDiff" */
    String name();

    /** 工具描述，帮助 LLM 理解工具的用途和参数，如 "获取指定 commit 的 diff 文本" */
    String description();

    /** 参数的 JSON Schema 定义，告诉 LLM 这个工具接受哪些参数、参数类型和是否必填 */
    Map<String, Object> parametersSchema();

    /** 执行工具，接收参数 Map，返回文本结果（作为 Observation 反馈给 LLM） */
    String execute(Map<String, String> params);

    /**
     * 是否并发安全。默认 true（只读工具），写操作的工具需覆写为 false。
     * 当前串行执行，此方法为未来并行扩展预留。
     */
    default boolean isConcurrencySafe() {
        return true;
    }
}
