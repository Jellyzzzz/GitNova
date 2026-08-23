package com.gitnova.service.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.schema.ToolSchemaValidator;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

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
@Service
public class ToolRegistry {
    private static final Logger logger= LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools;

    /**
     * Spring 自动注入所有 AgentTool 实现
     */
    public ToolRegistry(List<AgentTool> toolList) {
       Objects.requireNonNull(toolList,"toolList must not be null");
       Map<String,AgentTool>registered=new LinkedHashMap<>();
       for(AgentTool tool:toolList){
           Objects.requireNonNull(tool,"registered tool must not be null");
           ToolDefinition definition=Objects.requireNonNull(tool.definition(),"definition must not be null");
           String toolName=definition.name();
           AgentTool exiting=registered.putIfAbsent(toolName,tool);
           if(exiting!=null){
               throw new IllegalStateException(
                       "Duplicate tool name: " + toolName
               );
           }
       }
       tools=Collections.unmodifiableMap(registered);
    }

    /**
     * 返回暴露给模型的全部工具定义。
     */
    public List<ToolDefinition> definitions() {
        return definitions(tools.keySet());
    }

    /**
     * Returns only the definitions authorized by the server-selected task profile.
     */
    public List<ToolDefinition> definitions(Set<String> allowedTools) {
        Set<String> allowed = requireAllowedTools(allowedTools);
        List<ToolDefinition>definitions=new ArrayList<>(tools.size());
        for(AgentTool tool:tools.values()){
            if (allowed.contains(tool.definition().name())) {
                definitions.add(tool.definition());
            }
        }
        return List.copyOf(definitions);
    }

    /**
     * 分发执行：LLM 返回 ToolCall 后，根据 name 找到对应工具并执行。
     * 出错不抛异常，把错误信息作为 Observation 返回给 Agent，
     */
    public ToolResult execute(ToolExecutionContext execution, String toolName, JsonNode arguments) {
        Objects.requireNonNull(execution,"execution must not be null");
        Objects.requireNonNull(toolName,"toolName must not be null");
        if(toolName.isBlank()){
            return ToolResult.error(ToolStatus.INVALID_ARGUMENT,"INVALID_TOOL_NAME","Tool name must not be blank",false);
        }
        AgentTool tool=tools.get(toolName);
        if(tool==null) {
            return ToolResult.error(ToolStatus.INVALID_ARGUMENT,"UNKNOWN_TOOL","Tool '" + toolName + "' is not registered",false);
        }
        if(arguments==null){
            return ToolResult.error(ToolStatus.INVALID_ARGUMENT,"MISSING_TOOL_ARGUMENTS","Tool arguments must not be null",false);
        }
        List<String>errors= ToolSchemaValidator.validate(tool.definition(),arguments);
        if(!errors.isEmpty()){
            return ToolResult.error(ToolStatus.INVALID_ARGUMENT,
                    "SCHEMA_VALIDATION_FAILED",
                    "Invalid tool arguments",
                    false);
        }
        try{
            ToolResult result = tool.execute(execution, arguments);
            if(result==null) {
                logger.error("Tool returned null result: runId={}, turn={}, toolCallId={}, toolName={}",
                        execution.run().runId(),
                        execution.turn(),
                        execution.toolCallId(),
                        toolName);
                return ToolResult.error(ToolStatus.INTERNAL_ERROR,"NULL_TOOL_RESULT","Tool returned no result",false);
            }
            return result;
        }catch (Exception e){
            logger.error(
                    "Tool execution failed: runId={}, turn={}, toolCallId={}, toolName={}",
                    execution.run().runId(),
                    execution.turn(),
                    execution.toolCallId(),
                    toolName,
                    e
            );
            return ToolResult.error(
                    ToolStatus.INTERNAL_ERROR,
                    "TOOL_EXECUTION_FAILED",
                    "Tool execution failed",
                    false
            );
        }
    }

    /**
     * Executes a tool only when it is authorized by the same task profile that produced the
     * model-visible definitions. This second check prevents forged or stale tool calls from
     * bypassing definition filtering.
     */
    public ToolResult executeScoped(
            ToolExecutionContext execution,
            Set<String> allowedTools,
            String toolName,
            JsonNode arguments
    ) {
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            return ToolResult.error(
                    ToolStatus.INVALID_ARGUMENT,
                    "INVALID_TOOL_NAME",
                    "Tool name must not be blank",
                    false
            );
        }
        Set<String> allowed = requireAllowedTools(allowedTools);
        if (!allowed.contains(toolName)) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "TOOL_NOT_ALLOWED",
                    "Tool is not allowed for the current task profile",
                    false
            );
        }
        return execute(execution, toolName, arguments);
    }

    private Set<String> requireAllowedTools(Set<String> allowedTools) {
        Objects.requireNonNull(allowedTools, "allowedTools must not be null");
        LinkedHashSet<String> copied = new LinkedHashSet<>();
        for (String name : allowedTools) {
            Objects.requireNonNull(name, "allowed tool name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException(
                        "allowed tool name must not be blank"
                );
            }
            if (!tools.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Allowed tool is not registered: " + name
                );
            }
            copied.add(name);
        }
        return Collections.unmodifiableSet(copied);
    }
    public boolean isTerminal(String name){
        AgentTool tool=tools.get(name);
        return tool!=null&&tool.terminal();
    }
}
