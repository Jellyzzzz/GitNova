package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.context.ChangedFile;
import com.gitnova.service.agent.context.DiffManifest;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.DiffScope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 工具 3 — 列出 commit 涉及的所有变更文件
 *
 * 帮助 Agent 快速了解本次 commit 的范围，决定是否需要深入查看某些文件。
 */
@Component
public class ListChangesTool implements AgentTool {

    private final GitObjectReader gitObjectReader;
    private final ObjectMapper objectMapper;

    public ListChangesTool(GitObjectReader gitObjectReader, ObjectMapper objectMapper) {
        this.gitObjectReader = gitObjectReader;
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
        if (!(run.revisionScope() instanceof DiffScope diffScope)) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "REVIEW_DIFF_SCOPE_REQUIRED",
                    "listChanges requires a server-authorized Review DiffScope",
                    false
            );
        }
        String baseSha1 = diffScope.baseSha1().value();
        String targetSha1 = diffScope.targetSha1().value();
        try {
            CommitObject baseCommit = gitObjectReader.requireCommit(repoKey, baseSha1);
            CommitObject targetCommit = gitObjectReader.requireCommit(repoKey, targetSha1);
            int totalAddedLines = 0;
            int totalDeletedLines = 0;
            int totalHunks = 0;
            boolean containsBinary = false;
            List<ChangedFile> changedFiles = new ArrayList<>();

            Map<String, GitObjectId> baseFiles = baseCommit.mapping();
            Map<String, GitObjectId> targetFiles = targetCommit.mapping();
            Set<String> paths = new TreeSet<>();
            paths.addAll(baseFiles.keySet());
            paths.addAll(targetFiles.keySet());
            for (String path : paths) {
                String language = detectLanguage(path);
                int addedLines = 0;
                int deletedLines = 0;
                int hunks = 0;
                GitObjectId oldBlob = baseFiles.get(path);
                GitObjectId newBlob = targetFiles.get(path);
                if (Objects.equals(oldBlob, newBlob)) {
                    continue;
                }
                String changeType;
                if (oldBlob == null) {
                    changeType = "ADDED";
                } else if (newBlob == null) {
                    changeType = "DELETED";
                } else {
                    changeType = "MODIFIED";
                }
                byte[] oldBytes = new byte[0];
                byte[] newBytes = new byte[0];
                List<String> oldLines = new ArrayList<>();
                List<String> newLines = new ArrayList<>();
                if (oldBlob != null) {
                    oldBytes = gitObjectReader.requireBlob(repoKey, oldBlob.value());
                    if (!isBinary(oldBytes)) {
                        oldLines = toLines(oldBytes);
                    }
                }
                if (newBlob != null) {
                    newBytes = gitObjectReader.requireBlob(repoKey, newBlob.value());
                    if (!isBinary(newBytes)) {
                        newLines = toLines(newBytes);
                    }
                }
                boolean binary = isBinary(oldBytes) || isBinary(newBytes);
                if (!binary) {
                    Patch<String> patch = DiffUtils.diff(oldLines, newLines);
                    for (var delta : patch.getDeltas()) {
                        deletedLines += delta.getSource().size();
                        addedLines += delta.getTarget().size();
                    }
                    hunks = patch.getDeltas().size();
                    totalAddedLines += addedLines;
                    totalDeletedLines += deletedLines;
                    totalHunks += hunks;
                } else {
                    containsBinary = true;
                }
                ChangedFile changedFile = new ChangedFile(
                        path,
                        changeType,
                        language,
                        addedLines,
                        deletedLines,
                        hunks,
                        binary
                );
                changedFiles.add(changedFile);
            }
            DiffManifest diffManifest = new DiffManifest(
                    List.copyOf(changedFiles),
                    changedFiles.size(),
                    totalHunks,
                    totalAddedLines,
                    totalDeletedLines,
                    containsBinary
            );
            JsonNode payload = objectMapper.valueToTree(diffManifest);
            return ToolResult.success(payload);
        } catch (GitObjectReadException e) {
            return mapReadFailure(e);
        }
    }

    private ToolResult mapReadFailure(GitObjectReadException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND -> ToolResult.error(
                    ToolStatus.NOT_FOUND,
                    "GIT_OBJECT_NOT_FOUND",
                    "Required Git object was not found in the current repository",
                    false
            );
            case CORRUPT -> ToolResult.error(
                    ToolStatus.INTERNAL_ERROR,
                    "CORRUPT_GIT_OBJECT",
                    "Required Git object is corrupt or has an unexpected type",
                    false
            );
            case TRANSIENT -> ToolResult.error(
                    ToolStatus.TRANSIENT_ERROR,
                    "GIT_OBJECT_READ_FAILED",
                    "Git object storage is temporarily unavailable",
                    true
            );
        };
    }
    private List<String> toLines(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        return text.lines().toList();
    }

    private String detectLanguage(String path) {
        int lastDotIndex = path.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "unknown";
        }
        String extension = path.substring(lastDotIndex + 1);
        return switch (extension) {
            case "java" -> "java";
            case "py" -> "python";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "c", "cpp", "h", "cxx", "hpp" -> "cpp";
            default -> "unknown";
        };
    }

    private boolean isBinary(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
