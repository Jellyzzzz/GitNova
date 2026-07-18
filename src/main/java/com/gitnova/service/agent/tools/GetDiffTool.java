package com.gitnova.service.agent.tools;

import com.gitnova.service.GitletService;
import com.gitnova.service.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gitnova.storage.LocalObjectStorage;

/**
 * 工具 1 — 获取指定 commit 的 diff 文本
 *
 * Agent 审查代码的第一步就是调用此工具获取变更内容。
 */
@Component
public class GetDiffTool implements AgentTool {

    private final GitletService gitletService;
    private final LocalObjectStorage localObjectStorage;
    public GetDiffTool(GitletService gitletService, LocalObjectStorage localObjectStorage) {
        this.gitletService = gitletService;
        this.localObjectStorage = localObjectStorage;
    }

    @Override
    public String name() {
        return "getDiff";
    }

    @Override
    public String description() {
        return "获取指定 commit 的 diff 文本";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        //props.put("repoId", Map.of("type", "string", "description", "仓库ID"));
        props.put("commitSha1", Map.of("type", "string", "description", "commit的SHA-1"));
        schema.put("properties", props);
        schema.put("required", List.of("commitSha1"));  // repoKey 由 Loop 注入，不暴露给 LLM
        return schema;
    }

    @Override
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 从 ObjectStorage 读取 Commit，与父 Commit 比对生成 diff
        String repoKey = params.get("repoKey");  // Loop 注入
        String commitSha1 = params.get("commitSha1");  // LLM 传入
        if (repoKey == null || commitSha1 == null) return "Error: Missing required parameters.";
        // localObjectStorage.readObject(repoKey, commitSha1);
        // TODO: 从 ObjectStorage 读取 Commit，与父 Commit 比对生成 diff 文本
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
