package com.gitnova.service.agent;

import com.gitnova.dto.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册表 — 管理所有 AgentTool 实例
 *
 * 利用 Spring 的集合注入自动收集所有 {@code @Component} 标注的 AgentTool，
 * 无需手动 register()。新增工具只需新建一个 {@code @Component} 类并实现
 * AgentTool 接口，ToolRegistry 不用改一行代码。
 *
 * 💡 这是开闭原则在工具注册场景的具体应用。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    /**
     * Spring 自动注入所有 AgentTool 实现
     */
    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream()
            .collect(Collectors.toMap(AgentTool::name, t -> t, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 生成供 LLM 使用的工具定义列表
     * 对应 DeepSeek API 请求体里的 tools 字段
     */
    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersSchema()))
            .collect(Collectors.toList());
    }

    /**
     * 分发执行：LLM 返回 ToolCall 后，根据 name 找到对应工具并执行。
     * 出错不抛异常，把错误信息作为 Observation 返回给 Agent，
     * 让 Agent 自己决定怎么处理（这是 ReAct 优于硬编码的地方）。
     */
    public String execute(String toolName, Map<String, String> params) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return "Error: unknown tool '" + toolName + "'";
        }
        try {
            return tool.execute(params);
        } catch (Exception e) {
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }
}
