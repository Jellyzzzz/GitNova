package com.gitnova.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ReAct Code Review Agent — 核心循环
 *
 * 实现 Thought → Action → Observation 循环：
 * 1. 构造 System Prompt（角色、工具定义、工作流引导）
 * 2. 循环调用 LLM，解析 tool_calls
 * 3. 遇到 submitReview 工具调用时终止
 * 4. maxTurns = 10 硬上限，防止无限循环
 *
 * 💡 Design Note — 为什么手写而非用 LangChain4j？
 * 4 个工具 + 1 个循环，手写 200 行代码就够了。
 * 引入框架反而增加了黑盒依赖，面试时讲不清楚底层原理。
 */
@Service
public class CodeReviewAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAgentLoop.class);
    private static final int MAX_TURNS = 10;

    private final ToolRegistry toolRegistry;

    @Value("${gitnova.llm.api-key:}")
    private String apiKey;

    @Value("${gitnova.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${gitnova.llm.model:deepseek-chat}")
    private String model;

    public CodeReviewAgentLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 运行 Agent Loop，对指定 commit 进行 Code Review
     *
     * @param repoId     仓库 ID
     * @param commitSha1 commit SHA-1
     * @return review 结果 JSON 字符串（[] 表示无问题）
     */
    public String runAgentLoop(Long repoId, String commitSha1) {
        // TODO: Phase 4 — ReAct Agent Loop
        // 1. buildSystemPrompt() 构造 System Message
        // 2. 构造初始 user message
        // 3. for turn = 0..MAX_TURNS:
        //      callLLM(messages) → 解析响应
        //      如果有 tool_calls:
        //        toolRegistry.execute(name, params) → Observation
        //        如果 name == "submitReview" → 返回 reviewJson
        //      如果无 tool_calls → break（异常情况）
        // 4. 超过 MAX_TURNS → 降级处理
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }

    /**
     * 构造 System Prompt
     *
     * 包含角色设定、可用工具列表、推荐工作流、审查重点、输出格式要求。
     * 参考 SPEC v3.4 4.3 节 Prompt 模板。
     */
    private String buildSystemPrompt() {
        // TODO: Phase 4 — 构造完整的 System Prompt
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
