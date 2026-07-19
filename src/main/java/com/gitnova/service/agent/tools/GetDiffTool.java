package com.gitnova.service.agent.tools;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.service.GitletService;
import com.gitnova.service.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.gitnova.storage.ObjectStorage;

/**
 * 工具 1 — 获取指定 commit 的 diff 文本
 *
 * Agent 审查代码的第一步就是调用此工具获取变更内容。
 */
@Component
public class GetDiffTool implements AgentTool {

    private final GitletService gitletService;
    private final ObjectStorage objectStorage;

    public GetDiffTool(GitletService gitletService, ObjectStorage objectStorage) {
        this.gitletService = gitletService;
        this.objectStorage = objectStorage;
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
        byte[] bytes=objectStorage.readObject(repoKey,commitSha1);
        if(bytes==null) return "Error: Commit " + commitSha1 + " not found in repository.";
        Commit commit= Utils.deserialize(bytes,Commit.class);

        if(commit.getParentCommit()==null) {
            Map<String,String>mapping=commit.getMapping();
            StringBuilder sb=new StringBuilder();
            sb.append("Commit: ").append(commitSha1).append(" (initial commit)\n");
            sb.append("Files: ").append(mapping.size()).append(" new\n\n");

            for(Map.Entry<String,String>entry:mapping.entrySet()){
                String filePath=entry.getKey();
                String blobSha1=entry.getValue();
                byte[] content=objectStorage.readObject(repoKey,blobSha1);
                String[] lines = new String(content, StandardCharsets.UTF_8).split("\n", -1);

                sb.append("--- /dev/null\n");
                sb.append("+++ b/").append(filePath).append("\n");
                sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
                for (String line : lines) {
                    sb.append("+").append(line).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
        Commit parent=Utils.deserialize(objectStorage.readObject(repoKey,commit.getParentCommit()),Commit.class);
        Map<String,String>childMap=commit.getMapping();
        Map<String,String>parentMap=parent.getMapping();

        Set<String> newFiles = new HashSet<>(childMap.keySet());
        Set<String> deletedFiles = new HashSet<>(parentMap.keySet());
        newFiles.removeAll(parentMap.keySet());                    // child有,parent无
        deletedFiles.removeAll(childMap.keySet());                  // parent有,child无

        Set<String> modified = new HashSet<>(childMap.keySet());
        modified.retainAll(parentMap.keySet());                     // 两边都有
        modified.removeIf(f -> parentMap.get(f).equals(childMap.get(f)));  // SHA-1相同的排除
        StringBuilder sb=new StringBuilder();
        for (String filePath : newFiles) {
            String blobSha1 = childMap.get(filePath);
            byte[] content = objectStorage.readObject(repoKey, blobSha1);
            String blob = new String(content, StandardCharsets.UTF_8);
            String[] newLines = blob.split("\n", -1);
            sb.append("--- /dev/null\n");
            sb.append("+++ b/").append(filePath).append("\n");
            sb.append("@@ -0,0 +1,").append(newLines.length).append(" @@\n");
            for (String line : newLines) {
                sb.append("+").append(line).append("\n");
            }
            sb.append("\n");
        }
        for (String filePath : deletedFiles) {
            String blobSha1 = parentMap.get(filePath);
            byte[] content = objectStorage.readObject(repoKey, blobSha1);
            String blob = new String(content, StandardCharsets.UTF_8);
            String[] oldLines = blob.split("\n", -1);
            sb.append("--- a/").append(filePath).append("\n");
            sb.append("+++ /dev/null\n");
            sb.append("@@ -1,").append(oldLines.length).append(" +0,0 @@\n");
            for (String line : oldLines) {
                sb.append("-").append(line).append("\n");
            }
            sb.append("\n");
        }
        for (String filePath : modified) {
            String oldSha1 = parentMap.get(filePath);
            String newSha1 = childMap.get(filePath);
            String oldContent = new String(objectStorage.readObject(repoKey, oldSha1), StandardCharsets.UTF_8);
            String newContent = new String(objectStorage.readObject(repoKey, newSha1), StandardCharsets.UTF_8);
            List<String> oldLines = List.of(oldContent.split("\n", -1));
            List<String> newLines = List.of(newContent.split("\n", -1));

            Patch<String> patch = DiffUtils.diff(oldLines, newLines);
            sb.append("--- a/").append(filePath).append("\n");
            sb.append("+++ b/").append(filePath).append("\n");
            for (String line : UnifiedDiffUtils.generateUnifiedDiff(
                    "a/" + filePath, "b/" + filePath, oldLines, patch, 3)) {
                sb.append(line).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
