package com.gitnova.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Code Review Agent — Phase 4 核心
 *
 * 触发：push 成功后异步（通过 CodeReviewListener 调用）
 * 产出：review_comment 表写入 + WebSocket 推送
 *
 * ⚠️ Warning — LLM 输出不可信：
 * 即使 Prompt 要求 JSON，LLM 仍可能输出 Markdown 包裹的 JSON 或非法格式。
 * 解析前必须 strip ```json ... ``` 包裹，用 try-catch 包裹 JSON 解析，
 * 解析失败时将原始文本存入 comment 字段，severity 标为 info，
 * 不要因为 Agent 失败而影响 push 结果。
 */
@Service
public class CodeReviewAgent {

    @Value("${gitnova.llm.api-key:}")
    private String apiKey;

    @Value("${gitnova.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${gitnova.llm.model:deepseek-chat}")
    private String model;

    /**
     * 对 diff 进行 Code Review
     *
     * @param repoId     仓库 ID
     * @param commitSha1 commit SHA-1
     * @param diff       代码变更 diff 文本
     * @return JSON 格式的 review 结果数组
     */
    public String review(Long repoId, String commitSha1, String diff) {
        // TODO: Phase 4 — Code Review Agent 逻辑
        // 1. 构造 Prompt（要求 JSON 数组格式输出）
        // 2. 调用 LLM API（流式响应）
        // 3. 解析 JSON → 写入 review_comment 表
        // 4. 通过 WebSocket 推送给仓库成员
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
