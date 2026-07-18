package com.gitnova.service.agent.tools;

import com.gitnova.service.GitletService;
import com.gitnova.service.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具 3 — 列出 commit 涉及的所有变更文件
 *
 * 帮助 Agent 快速了解本次 commit 的范围，决定是否需要深入查看某些文件。
 */
@Component
public class ListChangedFilesTool implements AgentTool {

    private final GitletService gitletService;

    public ListChangedFilesTool(GitletService gitletService) {
        this.gitletService = gitletService;
    }

    @Override
    public String name() {
        return "listChangedFiles";
    }

    @Override
    public String description() {
        return "列出指定 commit 中所有变更的文件路径及其变更类型（新增/修改/删除）";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("commitSha1", Map.of("type", "string", "description", "commit的SHA-1"));
        schema.put("properties", props);
        schema.put("required", List.of("commitSha1"));  // repoKey 由 Loop 注入
        return schema;
    }

    @Override
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 对比 commit 与其父 commit 的 mapping，返回变更文件列表
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
