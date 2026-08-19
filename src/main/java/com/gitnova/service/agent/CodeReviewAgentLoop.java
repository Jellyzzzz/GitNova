package com.gitnova.service.agent;

import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.context.ReviewScope;
import com.gitnova.service.agent.context.Revision;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ReAct Code Review Agent — 核心循环
 *
 * 实现 Thought → Action → Observation 循环：
 * 1. 构造 System Prompt（角色、工具定义、工作流引导）
 * 2. 循环调用 LLM，解析 tool_calls
 * 3. 遇到 submitReview 工具调用时终止
 * 4. maxTurns = 10 硬上限，防止无限循环
 /**
 * Context Injection 安全设计：
 * repoId、repoKey 与审查 Revision 由 Listener 构造的
 * AgentRunContext 提供，模型不可控制。
 * 工具执行时先复制模型参数，再由 Harness 写入可信参数，
 * 从而无条件覆盖模型提供的同名值。
 * repoKey 的规范化与格式校验由 AgentRunContext 统一负责。
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

    // ===== 请求级上下文（由 Listener 传入，LLM 不可见） =====


    public CodeReviewAgentLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 运行 Agent Loop，对指定审查上下文执行 Code Review。
     *
     * @param context 本次 Agent Run 的不可变可信上下文
     * @return Review 结果 JSON 字符串
     */
    public String runAgentLoop(AgentRunContext context) {
        Objects.requireNonNull(context, "context must not be null");

        log.info(
                "Starting code review agent: runId={}, repoId={}, targetSha1={}",
                context.runId(),
                context.repoId(),
                context.targetSha1()
        );

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
     * v4.2 工具不再把可信上下文混入模型 arguments；Runtime 应将
     * AgentRunContext 放入 ToolExecutionContext 后交给 ToolRegistry。
     *
     * @param call LLM 返回的工具调用
     * @return 工具执行结果（Observation）
     */
    private String executeTool(AgentRunContext context, ToolCall call) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(call, "tool call must not be null");
        String toolName = Objects.requireNonNull(
                call.name(),
                "tool call name must not be null"
        );

        if (toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "tool call name must not be blank"
            );
        }
        // TODO: v4.2 — AgentRuntime will create ToolExecutionContext and dispatch via ToolRegistry.
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }

    /**
     * 构造 System Prompt
     *
     * 包含角色设定、可用工具列表、推荐工作流、审查重点、输出格式要求。
     * 参考 SPEC v3.4 4.3 节 Prompt 模板。
     */
    private String buildSystemPrompt(AgentRunContext context) {
        Objects.requireNonNull(context, "context must not be null");
        // TODO: Phase 4 — 构造完整的 System Prompt
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
