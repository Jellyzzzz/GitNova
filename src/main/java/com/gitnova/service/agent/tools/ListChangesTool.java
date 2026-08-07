package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitlet.Utils;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.gitobject.ObjectStorageGitObjectReader;
import com.gitnova.service.agent.context.ChangedFile;
import com.gitnova.service.agent.context.DiffManifest;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 工具 3 — 列出 commit 涉及的所有变更文件
 *
 * 帮助 Agent 快速了解本次 commit 的范围，决定是否需要深入查看某些文件。
 */
@Component
public class ListChangesTool implements AgentTool {

    private final GitObjectReader gitObjectReader;
    private final ObjectMapper objectMapper;

    public ListChangesTool(GitObjectReader objectStorageGitObjectReader, ObjectMapper objectMapper) {
        this.gitObjectReader = objectStorageGitObjectReader;
        this.objectMapper = objectMapper;
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
        DiffManifest diffManifest;
        boolean containsBinary=false;
        if (baseSha1 == null) {
            return ToolResult.error(ToolStatus.CONFLICT, "BASE_REVISION_MISSING", "Review context does not contain a BASE revision", false);
        }
        try {
        Commit baseCommit=gitObjectReader.requireCommit(repoKey,baseSha1);
        Commit targetCommit=gitObjectReader.requireCommit(repoKey,targetSha1);
        int totalAddedLines=0;
        int totalDeletedLines=0;
        int totalHunks=0;
        List<ChangedFile>changedFiles=new ArrayList<>();

            Map<String, String> baseFiles = baseCommit.getMapping();
            Map<String, String> targetFiles = targetCommit.getMapping();
            Set<String> paths = new TreeSet<>();
            paths.addAll(baseFiles.keySet());
            paths.addAll(targetFiles.keySet());
            for (String path : paths) {
                String language=detectLanguage(path);
                int addedLines=0;
                int deletedLines=0;
                int hunks=0;
                String oldBlob=baseFiles.get(path);
                String newBlob=targetFiles.get(path);
                if(Objects.equals(oldBlob,newBlob)) continue;
                String changeType;
                if(oldBlob==null) changeType="ADDED";
                else if(newBlob==null) changeType="DELETED";
                else changeType="MODIFIED";
                byte[] oldBytes = new byte[0];
                byte[] newBytes = new byte[0];
                List<String>oldLines=new ArrayList<>();
                List<String>newLines=new ArrayList<>();
                if(oldBlob!=null){
                    oldBytes=gitObjectReader.requireBlob(repoKey,oldBlob);
                    if(!isBinary(oldBytes)) oldLines=toLines(oldBytes);
                }
                if(newBlob!=null){
                    newBytes=gitObjectReader.requireBlob(repoKey,newBlob);
                    if(!isBinary(newBytes)) newLines=toLines(newBytes);
                }
                boolean binary=isBinary(oldBytes)||isBinary(newBytes);
                if(!binary) {
                    Patch<String> patch = DiffUtils.diff(oldLines, newLines);
                    for (var delta : patch.getDeltas()) {
                        deletedLines += delta.getSource().size();
                        addedLines += delta.getTarget().size();
                    }
                    hunks = patch.getDeltas().size();
                    totalAddedLines += addedLines;
                    totalDeletedLines += deletedLines;
                    totalHunks += hunks;
                }
                else containsBinary=true;
                ChangedFile changedFile=new ChangedFile(path,changeType,language,addedLines,deletedLines,hunks,binary);
                changedFiles.add(changedFile);
            }
            diffManifest=new DiffManifest(changedFiles,changedFiles.size(),totalHunks,totalAddedLines,totalDeletedLines,containsBinary);
            JsonNode payload=objectMapper.valueToTree(diffManifest);
            return ToolResult.success(payload);
        }catch(GitObjectReadException e){
            return ToolResult.error(ToolStatus.NOT_FOUND,
                    "REVISION_NOT_FOUND",
                    "Review revision does not exist in the current repository",
                    false);
        }
    }
    private List<String>toLines(byte[] content){
        String text=new String(content, StandardCharsets.UTF_8);
        return text.lines().toList();
    }
    private String detectLanguage(String path){
        int lastDotIndex=path.lastIndexOf('.');
        if(lastDotIndex==-1) return "unknown";
        String extraName=path.substring(lastDotIndex+1);
        if(extraName.equals("java")) return "java";
        else if(extraName.equals("py")) return "python";
        else if(extraName.equals("js")) return "javascript";
        else if(extraName.equals("ts")) return "typescript";
        else if(extraName.equals("c")||extraName.equals("cpp")||extraName.equals("h")||extraName.equals("cxx")||extraName.equals("hpp"))
            return "cpp";
        else return "unknown";
    }
    private boolean isBinary(byte[] content){
        if(content==null) return false;
        for(byte b:content){
            if(b==0) return true;
        }
        return false;
    }
}
