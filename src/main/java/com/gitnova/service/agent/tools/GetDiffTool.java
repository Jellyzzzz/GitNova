package com.gitnova.service.agent.tools;

import com.gitnova.service.GitletService;
import com.gitnova.service.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具 1 — 获取指定 commit 的 diff 文本
 *
 * Agent 审查代码的第一步就是调用此工具获取变更内容。
 */
@Component
public class GetDiffTool implements AgentTool {

    private final GitletService gitletService;

    public GetDiffTool(GitletService gitletService) {
        this.gitletService = gitletService;
    }

    @Override
    public String name() {
        return "getDiff";
    }

    @Override
    public String description() {
        return "获取指定 commit 的 diff 文本。参数: repoId(仓库ID), commitSha1(commit的SHA-1)";
    }

    @Override
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 从 ObjectStorage 读取 Commit，与父 Commit 比对生成 diff
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
