package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具 3 — 列出 commit 涉及的所有变更文件
 *
 * 帮助 Agent 快速了解本次 commit 的范围，决定是否需要深入查看某些文件。
 */
@Component
public class ListChangesTool implements AgentTool {

    private final ObjectStorageGitObjectReader objectStorageGitObjectReader;

    public ListChangesTool(ObjectStorageGitObjectReader objectStorageGitObjectReader) {
        this.objectStorageGitObjectReader = objectStorageGitObjectReader;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return new ToolDefinition("listChanges",
                "Lists files changed from BASE to TARGET in the current review range",
                schema);
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        AgentRunContext run = execution.run();
        String repoKey = run.repoKey();
        String baseSha1 = run.baseSha1();
        String targetSha1 = run.targetSha1();
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        ArrayNode files = payload.putArray("files");

        if (baseSha1 == null) {
            return ToolResult.error(ToolStatus.CONFLICT, "BASE_REVISION_MISSING", "Review context does not contain a BASE revision", false);
        }

        Commit baseCommit=objectStorageGitObjectReader.requireCommit(repoKey,baseSha1);
        Commit targetCommit=objectStorageGitObjectReader.requireCommit(repoKey,targetSha1);
        if (baseCommit==null||targetCommit==null) {
            return ToolResult.error(ToolStatus.NOT_FOUND,
                    "REVISION_NOT_FOUND",
                    "Review revision does not exist in the current repository",
                    false);
        }
        Map<String, String> baseFiles = baseCommit.getMapping();
        Map<String, String> targetFiles = targetCommit.getMapping();
        Set<String> paths = new TreeSet<>();
        paths.addAll(baseFiles.keySet());
        paths.addAll(targetFiles.keySet());
        for (String path : paths) {
            String changeType = "";
            if (!baseFiles.containsKey(path) && targetFiles.containsKey(path)) {
                changeType = "ADDED";
            } else if (baseFiles.containsKey(path) && !targetFiles.containsKey(path)) {
                changeType = "DELETED";
            } else if (baseFiles.containsKey(path) && targetFiles.containsKey(path)) {
                String baseBlob = baseFiles.get(path);
                String targetBlob = targetFiles.get(path);
                if (!baseBlob.equals(targetBlob)) {
                    changeType = "MODIFIED";
                }
            }
            if(changeType.isEmpty()) continue;
            ObjectNode file = files.addObject();
            file.put("path", path);
            file.put("changeType", changeType);
        }
        payload.put("totalFiles", files.size());
        return ToolResult.success(payload);
    }
}
