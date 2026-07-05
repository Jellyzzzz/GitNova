package com.gitnova.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Commit Message 生成 Agent — Phase 4（最轻量，可优先做）
 *
 * 触发：POST /api/repos/{repoId}/suggest-message
 * 输入：当前 staged 文件 diff
 * 产出：返回建议的 commit message 字符串
 *
 * 💡 建议实现顺序：7-B → 7-A → 7-C
 * 7-B 半天能跑通，建立信心；7-A 是简历主打；7-C 是演示彩蛋。
 */
@Service
public class CommitMessageAgent {

    @Value("${gitnova.llm.api-key:}")
    private String apiKey;

    @Value("${gitnova.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${gitnova.llm.model:deepseek-chat}")
    private String model;

    /**
     * 根据 diff 生成建议的 commit message
     *
     * @param diff 代码变更内容
     * @return 建议的 commit message
     */
    public String suggest(String diff) {
        // TODO: Phase 4 — Commit Message Agent 逻辑
        // 1. 构造 Prompt：根据 diff 生成约定式提交信息
        // 2. 调用 LLM API
        // 3. 返回生成的 message
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
