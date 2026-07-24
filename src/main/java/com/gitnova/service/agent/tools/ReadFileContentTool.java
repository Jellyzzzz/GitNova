package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.AgentTool;
import com.gitnova.service.agent.ToolResult;
import com.gitnova.storage.ObjectStorage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具 2 — 读取指定文件的完整内容
 *
 * 这是区分"流水线"和"Agent"的关键：Agent 自主决定是否需要查看上下文文件。
 * 例如 diff 里改了方法签名，Agent 会主动读调用方文件确认是否适配。
 */
// @Component // v4.2 migration pending, temporarily disabled
public class ReadFileContentTool implements AgentTool {

    private final ObjectStorage objectStorage;

    public ReadFileContentTool(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public ToolDefinition definition() {
        throw new UnsupportedOperationException("v4.2 migration pending");
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        throw new UnsupportedOperationException("v4.2 migration pending");
    }

    // === v3.6 旧代码保留 ===

    public String name() {
        return "readFileContent";
    }

    public String description() {
        return "读取指定文件在某个 commit 下的完整内容";
    }

    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("filePath", Map.of("type", "string", "description", "文件路径"));
        props.put("commitSha1", Map.of("type", "string", "description", "commit的SHA-1"));
        schema.put("properties", props);
        schema.put("required", List.of("filePath", "commitSha1"));
        return schema;
    }

    /** @deprecated v4.2 待迁移 */
    public String execute(Map<String, String> params) {
        // TODO: Phase 4 — 根据 filePath + commitSha1 从 ObjectStorage 读取文件内容
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
