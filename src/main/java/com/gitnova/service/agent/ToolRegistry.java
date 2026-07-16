package com.gitnova.service.agent;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具注册表 — 管理所有 AgentTool 实例
 *
 * 利用 Spring 的集合注入自动收集所有 {@code @Component} 标注的 AgentTool，
 * 无需手动 register()。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    /**
     * Spring 自动注入所有 AgentTool 实现
     */
    public ToolRegistry(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
    }

    /** 按名称执行工具 */
    public String execute(String name, Map<String, String> params) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "Error: 未知工具 '" + name + "', 可用工具: " + tools.keySet();
        }
        return tool.execute(params);
    }

    /** 返回工具定义列表，供 LLM 的 tool_choice 使用 */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            // 参数 schema 由各工具自行提供（可扩展）
            def.put("function", function);
            defs.add(def);
        }
        return defs;
    }
}
