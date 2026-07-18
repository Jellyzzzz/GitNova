package com.gitnova.service.agent.tools;

import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.service.GitletService;
import com.gitnova.service.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                String[] lines=new String(content).split("\n");

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
        throw new UnsupportedOperationException("Phase 4: 待实现");
    }
}
