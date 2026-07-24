package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.AgentTool;
import com.gitnova.service.agent.ToolAccessMode;
import com.gitnova.service.agent.ToolResult;
import com.gitnova.service.agent.ToolStatus;
import org.springframework.stereotype.Component;

/**
 * 工具 — 请求终止审查（逻辑操作）
 *
 * v4.2: 不再写库或推 WebSocket，仅返回结构化 ReviewDraft。
 * Agent Loop 检测到 finalizeReview 调用时终止循环。
 */
@Component
public class SubmitReviewTool implements AgentTool {

    private static final ToolDefinition DEFINITION;

    static {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("commitSha1")
                .put("type", "string")
                .put("description", "commit 的 SHA-1");
        props.putObject("reviewJson")
                .put("type", "string")
                .put("description", "JSON 数组格式的 review 结果");

        ObjectNode required = JsonNodeFactory.instance.objectNode();
        schema.set("required", schema.has("required") ? schema.get("required") : JsonNodeFactory.instance.arrayNode().add("commitSha1").add("reviewJson"));
        // 简化：直接构造 required 数组
        schema.putArray("required").add("commitSha1").add("reviewJson");

        DEFINITION = new ToolDefinition(
                "submitReview",
                "提交最终的 Code Review 结果。repoKey/repoId 由 Harness 注入，模型不传。",
                schema
        );
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    /** 逻辑终止操作，非仓库写操作 */
    @Override
    public ToolAccessMode accessMode() {
        return ToolAccessMode.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        // TODO: Phase 4 — 解析 reviewJson，构造 ReviewDraft
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
