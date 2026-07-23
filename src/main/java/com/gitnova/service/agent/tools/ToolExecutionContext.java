package com.gitnova.service.agent.tools;

import com.gitnova.service.agent.AgentRunContext;

import java.util.Objects;

/**
 * 一次工具调用的可信执行上下文。
 *
 * 与模型传入的 arguments 分离：
 * run、turn、toolCallId 均由 Harness 创建，
 * 模型不能直接修改这些字段。
 *
 * @param run        本次工具调用所属的 Agent Run
 * @param turn       当前 Agent 循环轮次，从 0 开始
 * @param toolCallId 模型返回的工具调用 ID
 */
public record ToolExecutionContext(
        AgentRunContext run,
        int turn,
        String toolCallId
) {
    public ToolExecutionContext{
        Objects.requireNonNull(run,"run must not be null");
        Objects.requireNonNull(toolCallId,"toolCallId must not be null");

        if(turn<0){
            throw new IllegalArgumentException("turn must not be negative");
        }
        if(toolCallId.isBlank()){
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
    }
}
