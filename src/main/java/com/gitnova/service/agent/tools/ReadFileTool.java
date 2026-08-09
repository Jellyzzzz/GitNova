package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitlet.Commit;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.context.Revision;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Reads a bounded line range from a trusted BASE or TARGET snapshot. */
@Component
public class ReadFileTool implements AgentTool {

    private static final int MAX_LINES_PER_CALL = 200;
    private static final int MAX_FILE_BYTES = 1024 * 1024;

    private final GitObjectReader gitObjectReader;

    public ReadFileTool(GitObjectReader gitObjectReader) {
        this.gitObjectReader = gitObjectReader;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("revision").put("type", "string");
        properties.putObject("filePath").put("type", "string");
        properties.putObject("startLine").put("type", "integer");
        properties.putObject("endLine").put("type", "integer");
        schema.putArray("required")
                .add("revision")
                .add("filePath")
                .add("startLine")
                .add("endLine");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "readFile",
                "Reads a bounded line range from BASE or TARGET in the current repository",
                schema
        );
    }

    @Override
    public ToolResult execute(ToolExecutionContext execution, JsonNode arguments) {
        AgentRunContext run = execution.run();
        String filePath = arguments.path("filePath").asText();
        int startLine = arguments.path("startLine").asInt();
        int requestedEndLine = arguments.path("endLine").asInt();

        if (!isSafeRepositoryPath(filePath)) {
            return ToolResult.error(
                    ToolStatus.PERMISSION_DENIED,
                    "INVALID_REPOSITORY_PATH",
                    "File path must be a normalized repository-relative path",
                    false
            );
        }
        if (startLine < 1 || requestedEndLine < startLine) {
            return invalidArgument(
                    "INVALID_LINE_RANGE",
                    "Line range must be positive and endLine must not precede startLine"
            );
        }
        if ((long) requestedEndLine - startLine + 1 > MAX_LINES_PER_CALL) {
            return invalidArgument(
                    "LINE_RANGE_TOO_LARGE",
                    "A readFile call may return at most " + MAX_LINES_PER_CALL + " lines"
            );
        }

        Revision revision;
        try {
            revision = Revision.valueOf(
                    arguments.path("revision")
                            .asText()
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            return invalidArgument(
                    "INVALID_REVISION",
                    "revision must be BASE or TARGET"
            );
        }
        String revisionSha = revision == Revision.TARGET
                ? run.targetSha1()
                : run.baseSha1();
        if (revisionSha == null) {
            return ToolResult.error(
                    ToolStatus.CONFLICT,
                    "BASE_REVISION_MISSING",
                    "Review context does not contain a BASE revision",
                    false
            );
        }

        try {
            Commit commit = gitObjectReader.requireCommit(
                    run.repoKey(),
                    revisionSha
            );
            String blobSha = commit.getMapping().get(filePath);
            if (blobSha == null) {
                return ToolResult.error(
                        ToolStatus.NOT_FOUND,
                        "FILE_NOT_FOUND",
                        "File does not exist at the requested revision",
                        false
                );
            }
            byte[] content = gitObjectReader.requireBlob(run.repoKey(), blobSha);
            if (content.length > MAX_FILE_BYTES) {
                return invalidArgument(
                        "FILE_TOO_LARGE",
                        "File exceeds the readFile size limit"
                );
            }
            if (isBinary(content)) {
                return invalidArgument(
                        "BINARY_FILE_UNSUPPORTED",
                        "readFile only supports text files"
                );
            }
            List<String> allLines = decodeLines(content);
            if (!allLines.isEmpty() && startLine > allLines.size()) {
                return invalidArgument(
                        "LINE_RANGE_OUT_OF_BOUNDS",
                        "startLine exceeds the number of lines in the file"
                );
            }
            int actualEndLine = Math.min(requestedEndLine, allLines.size());
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("revision", revision.name());
            payload.put("filePath", filePath);
            payload.put("startLine", startLine);
            payload.put("endLine", actualEndLine);
            payload.put("totalLines", allLines.size());
            ArrayNode lines = payload.putArray("lines");
            for (int lineNumber = startLine; lineNumber <= actualEndLine; lineNumber++) {
                ObjectNode line = lines.addObject();
                line.put("lineNumber", lineNumber);
                line.put("content", allLines.get(lineNumber - 1));
            }
            return ToolResult.success(payload);
        } catch (GitObjectReadException e) {
            return mapReadFailure(e);
        } catch (CharacterCodingException e) {
            return invalidArgument(
                    "UNSUPPORTED_FILE_ENCODING",
                    "readFile only supports valid UTF-8 text files"
            );
        }
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
}
