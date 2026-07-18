package com.gitnova.service.agent;

import com.gitnova.dto.ToolCall;
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
 * 🔒 Context Injection 安全设计：
 * repoKey 由 Listener 查库拼接后传入，LLM 不可见也不可控。
 * 工具执行前由 Loop 注入 repoKey，且框架层永远覆盖 LLM 的同名参数。
 * 防止路径穿越攻击：repoKey 仅允许 \d+/\d+ 格式。
 *
 * 💡 Design Note — 为什么手写而非用 LangChain4j？
 * 4 个工具 + 1 个循环，手写 200 行代码就够了。
 * 引入框架反而增加了黑盒依赖，面试时讲不清楚底层原理。
 */
@Service
public class CodeReviewAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAgentLoop.class);
    private static final int MAX_TURNS = 10;

    /** {@code \d+/\d+} — 防止路径穿越攻击 */
    private static final String REPO_KEY_PATTERN = "\\d+/\\d+";

    private final ToolRegistry toolRegistry;

    @Value("${gitnova.llm.api-key:}")
    private String apiKey;

    @Value("${gitnova.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${gitnova.llm.model:deepseek-chat}")
    private String model;

    // ===== 请求级上下文（由 Listener 传入，LLM 不可见） =====

    /** 当前审查的仓库 ID */
    private Long currentRepoId;

    /** 仓库路径标识（"{ownerId}/{repoId}"），由 Listener 从 DB 查得 */
    private String currentRepoKey;

    public CodeReviewAgentLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 运行 Agent Loop，对指定 commit 进行 Code Review
     *
     * @param repoId     仓库 ID
     * @param repoKey    仓库路径标识（Listener 查库拼接，防路径伪造）
     * @param commitSha1 commit SHA-1
     * @return review 结果 JSON 字符串（[] 表示无问题）
     */
    public String runAgentLoop(Long repoId, String repoKey, String commitSha1) {
        this.currentRepoId = repoId;
        this.currentRepoKey = repoKey;

        // 防御：校验 repoKey 格式
        if (!repoKey.matches(REPO_KEY_PATTERN)) {
            log.error("Invalid repoKey format: {}", repoKey);
            return "[]";  // 降级：返回空 review
        }

        // TODO: Phase 4 — ReAct Agent Loop
        // 1. buildSystemPrompt() 构造 System Message
        // 2. 构造初始 user message
        // 3. for turn = 0..MAX_TURNS:
        //      callLLM(messages) → 解析响应
        //      如果有 tool_calls:
        //        对每个 ToolCall 调用 executeTool(call)
        //        如果 name == "submitReview" → 返回 reviewJson
        //      如果无 tool_calls → break（异常情况）
        // 4. 超过 MAX_TURNS → 降级处理
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }

    /**
     * 执行工具 — 注入上下文参数 + 安全防御
     *
     * 🔒 安全原则：
     * 1. 先放 LLM 参数 (call.params)，再放框架参数 (repoKey)
     *    顺序保证框架覆盖 LLM，类似 HTTP header 注入防御
     * 2. submitReview 是终止信号，额外注入 repoId 供写库
     *
     * @param call LLM 返回的工具调用
     * @return 工具执行结果（Observation）
     */
    private String executeTool(ToolCall call) {
        Map<String, String> merged = new LinkedHashMap<>();
        // ① 先放 LLM 参数（不可信）
        merged.putAll(call.getParams());
        // ② 框架注入，后放覆盖同名参数（防 LLM 注入攻击）
        merged.put("repoKey", currentRepoKey);

        if ("submitReview".equals(call.getName())) {
            merged.putIfAbsent("repoId", String.valueOf(currentRepoId));
        }

        return toolRegistry.execute(call.getName(), merged);
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
