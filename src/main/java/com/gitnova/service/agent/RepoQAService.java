package com.gitnova.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 仓库问答 Agent / RAG — Phase 4（彩蛋，有余力再做）
 *
 * 触发：POST /api/repos/{repoId}/chat
 * 输入：用户自然语言提问
 * 产出：基于仓库代码上下文的回答
 *
 * 实现思路：代码文件分块 → Embedding → VectorStore（本地 Chroma）→ RAG
 */
@Service
public class RepoQAService {

    @Value("${gitnova.llm.api-key:}")
    private String apiKey;

    @Value("${gitnova.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${gitnova.llm.model:deepseek-chat}")
    private String model;

    /**
     * 基于仓库代码上下文回答问题
     *
     * @param repoId   仓库 ID
     * @param question 用户自然语言提问
     * @return 基于代码上下文的回答
     */
    public String chat(Long repoId, String question) {
        // TODO: Phase 4 (可选) — RAG 问答逻辑
        // 1. 检索相关代码文件
        // 2. 构造包含代码上下文的 Prompt
        // 3. 调用 LLM API
        // 4. 返回回答
        throw new UnsupportedOperationException("Phase 4 (可选): 待实现");
    }
}
