package com.gitnova.controller;

import com.gitnova.dto.ApiResponse;
import com.gitnova.service.agent.CommitMessageService;
import com.gitnova.service.agent.RepoQAService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 相关接口 — Phase 4
 */
@RestController
@RequestMapping("/api/repos/{repoId}")
public class AgentController {

    private final CommitMessageService commitMessageService;
    private final RepoQAService repoQAService;

    public AgentController(CommitMessageService commitMessageService,
                           RepoQAService repoQAService) {
        this.commitMessageService = commitMessageService;
        this.repoQAService = repoQAService;
    }

    /**
     * 7-B：生成建议的 commit message
     *
     * 输入：当前 staged 文件的 diff
     * 产出：建议的 commit message 字符串
     */
    @PostMapping("/suggest-message")
    public ApiResponse<String> suggestMessage(@PathVariable Long repoId,
                                               @RequestParam String diff) {
        String suggested = commitMessageService.suggest(diff);
        return ApiResponse.success(suggested);
    }

    /**
     * 7-C：仓库问答 / RAG
     *
     * 输入：用户自然语言提问
     * 产出：基于仓库代码上下文的回答
     */
    @PostMapping("/chat")
    public ApiResponse<String> chat(@PathVariable Long repoId,
                                     @RequestParam String question) {
        String answer = repoQAService.chat(repoId, question);
        return ApiResponse.success(answer);
    }
}
