package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.DiffScope;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Returns semantic unified-diff hunks for one file in the trusted review range. */
@Component
public class GetDiffTool implements AgentTool {

    private static final Pattern CURSOR_PATTERN = Pattern.compile("h([1-9]\\d*)");
    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$"
    );
    private static final int MAX_HUNKS_PER_CALL = 20;
    private static final int MAX_CONTEXT_LINES = 20;

    private final GitObjectReader gitObjectReader;

    public GetDiffTool(GitObjectReader gitObjectReader) {
        this.gitObjectReader = gitObjectReader;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("filePath").put("type", "string");
        properties.putObject("cursor")
                .putArray("type")
                .add("string")
                .add("null");
        properties.putObject("maxHunks").put("type", "integer");
        properties.putObject("contextLines").put("type", "integer");
        schema.putArray("required")
                .add("filePath")
                .add("maxHunks")
                .add("contextLines");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "getDiff",
                "Returns paginated diff hunks for one file changed from BASE to TARGET",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        AgentRunContext run = execution.run();
        String filePath = arguments.path("filePath").asText();
        int maxHunks = arguments.path("maxHunks").asInt();
        int contextLines = arguments.path("contextLines").asInt();
        String cursor = arguments.hasNonNull("cursor")
                ? arguments.path("cursor").asText()
                : null;

        ToolResult validationError = validateArguments(
                filePath,
                cursor,
                maxHunks,
                contextLines
        );
        if (validationError != null) {
            return validationError;
        }
        if (!(run.revisionScope() instanceof DiffScope diffScope)) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "REVIEW_DIFF_SCOPE_REQUIRED",
                    "getDiff requires a server-authorized Review DiffScope",
                    false
            );
        }

        try {
            CommitObject base = gitObjectReader.requireCommit(
                    run.repoKey(),
                    diffScope.baseSha1().value()
            );
            CommitObject target = gitObjectReader.requireCommit(
                    run.repoKey(),
                    diffScope.targetSha1().value()
            );
            GitObjectId oldBlob = base.mapping().get(filePath);
            GitObjectId newBlob = target.mapping().get(filePath);

            if (Objects.equals(oldBlob, newBlob)) {
                return ToolResult.error(
                        ToolStatus.PERMISSION_DENIED,
                        "FILE_OUTSIDE_CHANGE_SCOPE",
                        "Requested file is not changed in the current review range",
                        false
                );
            }

            byte[] oldBytes = oldBlob == null
                    ? new byte[0]
                    : gitObjectReader.requireBlob(run.repoKey(), oldBlob.value());
            byte[] newBytes = newBlob == null
                    ? new byte[0]
                    : gitObjectReader.requireBlob(run.repoKey(), newBlob.value());
            if (isBinary(oldBytes) || isBinary(newBytes)) {
                return ToolResult.error(
                        ToolStatus.INVALID_ARGUMENT,
                        "BINARY_DIFF_UNSUPPORTED",
                        "Diff hunks are not available for binary files",
                        false
                );
            }

            List<String> oldLines = decodeLines(oldBytes);
            List<String> newLines = decodeLines(newBytes);
            List<DiffHunk> hunks = buildHunks(
                    filePath,
                    oldBlob == null ? null : oldBlob.value(),
                    newBlob == null ? null : newBlob.value(),
                    oldLines,
                    newLines,
                    contextLines
            );
            int startIndex = cursorStartIndex(cursor, hunks.size());
            if (startIndex < 0) {
                return invalidArgument(
                        "INVALID_DIFF_CURSOR",
                        "Cursor does not identify a hunk in this diff"
                );
            }

            int endIndex = Math.min(startIndex + maxHunks, hunks.size());
            boolean hasMore = endIndex < hunks.size();
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("filePath", filePath);
            ArrayNode hunkArray = payload.putArray("hunks");
            for (int index = startIndex; index < endIndex; index++) {
                DiffHunk hunk = hunks.get(index);
                ObjectNode hunkNode = hunkArray.addObject();
                hunkNode.put("hunkId", "h" + (index + 1));
                hunkNode.put("oldStart", hunk.oldStart());
                hunkNode.put("newStart", hunk.newStart());
                ArrayNode lines = hunkNode.putArray("lines");
                hunk.lines().forEach(lines::add);
            }
            if (hasMore) {
                payload.put("nextCursor", "h" + (endIndex + 1));
            } else {
                payload.putNull("nextCursor");
            }
            payload.put("hasMore", hasMore);
            return ToolResult.success(payload, hasMore);
        } catch (GitObjectReadException e) {
            return mapReadFailure(e);
        } catch (CharacterCodingException e) {
            return ToolResult.error(
                    ToolStatus.INVALID_ARGUMENT,
                    "UNSUPPORTED_FILE_ENCODING",
                    "Diff is only available for valid UTF-8 text files",
                    false
            );
        }
    }

    private ToolResult validateArguments(
            String filePath,
            String cursor,
            int maxHunks,
            int contextLines
    ) {
        if (!isSafeRepositoryPath(filePath)) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "INVALID_REPOSITORY_PATH",
                    "File path must be a normalized repository-relative path",
                    false
            );
        }
        if (maxHunks < 1 || maxHunks > MAX_HUNKS_PER_CALL) {
            return invalidArgument(
                    "INVALID_MAX_HUNKS",
                    "maxHunks must be between 1 and " + MAX_HUNKS_PER_CALL
            );
        }
        if (contextLines < 0 || contextLines > MAX_CONTEXT_LINES) {
            return invalidArgument(
                    "INVALID_CONTEXT_LINES",
                    "contextLines must be between 0 and " + MAX_CONTEXT_LINES
            );
        }
        if (cursor != null && !CURSOR_PATTERN.matcher(cursor).matches()) {
            return invalidArgument(
                    "INVALID_DIFF_CURSOR",
                    "Cursor must use the hunk format hN"
            );
        }
        return null;
    }

    private int cursorStartIndex(String cursor, int hunkCount) {
        if (cursor == null) {
            return 0;
        }
        Matcher matcher = CURSOR_PATTERN.matcher(cursor);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            return index >= 0 && index < hunkCount ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<DiffHunk> buildHunks(
            String filePath,
            String oldBlob,
            String newBlob,
            List<String> oldLines,
            List<String> newLines,
            int contextLines
    ) {
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        String oldName = oldBlob == null ? "/dev/null" : "a/" + filePath;
        String newName = newBlob == null ? "/dev/null" : "b/" + filePath;
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                oldName,
                newName,
                oldLines,
                patch,
                contextLines
        );
        List<DiffHunk> hunks = new ArrayList<>();
        int oldStart = 0;
        int newStart = 0;
        List<String> currentLines = null;
        for (String line : unified) {
            Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                if (currentLines != null) {
                    hunks.add(new DiffHunk(oldStart, newStart, List.copyOf(currentLines)));
                }
                oldStart = Integer.parseInt(matcher.group(1));
                newStart = Integer.parseInt(matcher.group(2));
                currentLines = new ArrayList<>();
                currentLines.add(line);
            } else if (currentLines != null) {
                currentLines.add(line);
            }
        }
        if (currentLines != null) {
            hunks.add(new DiffHunk(oldStart, newStart, List.copyOf(currentLines)));
        }
        return hunks;
    }

    private List<String> decodeLines(byte[] content)
            throws CharacterCodingException {
        if (content.length == 0) {
            return List.of();
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        return text.lines().toList();
    }

    private boolean isBinary(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeRepositoryPath(String path) {
        if (path == null || path.isBlank() || path.length() > 4096) {
            return false;
        }
        if (path.indexOf('\0') >= 0
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.matches("^[A-Za-z]:.*")
                || path.contains("\\")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
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

    private ToolResult invalidArgument(String code, String message) {
        return ToolResult.error(
                ToolStatus.INVALID_ARGUMENT,
                code,
                message,
                false
        );
    }

    private record DiffHunk(
            int oldStart,
            int newStart,
            List<String> lines
    ) {
    }
}
