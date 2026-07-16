package com.gitnova.service.agent.tools;

import com.gitnova.service.agent.AgentTool;
import com.gitnova.storage.ObjectStorage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具 2 — 读取指定文件的完整内容
 *
 * 这是区分"流水线"和"Agent"的关键：Agent 自主决定是否需要查看上下文文件。
 * 例如 diff 里改了方法签名，Agent 会主动读调用方文件确认是否适配。
 */
@Component
public class ReadFileContentTool implements AgentTool {

    private final ObjectStorage objectStorage;

    public ReadFileContentTool(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public String name() {
        return "readFileContent";
    }

    @Override
    public String description() {
        return "读取指定文件在某个 commit 下的完整内容。参数: repoId(仓库ID), filePath(文件路径), commitSha1(commit的SHA-1)";
    }

    @Override
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 根据 filePath + commitSha1 从 ObjectStorage 读取文件内容
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
