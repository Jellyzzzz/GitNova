package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.context.Revision;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;
import com.gitnova.service.agent.workspace.DiffScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Reads a bounded line range from a trusted BASE, TARGET, or Workspace snapshot. */
@Component
public class ReadFileTool implements AgentTool {

    private static final int MAX_LINES_PER_CALL = 200;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_RETURNED_BYTES = 24 * 1024;

    private final GitObjectReader gitObjectReader;
    private final WorkspaceGateway workspaceGateway;

    public ReadFileTool(GitObjectReader gitObjectReader) {
        this(gitObjectReader, (WorkspaceGateway) null);
    }

    public ReadFileTool(
            GitObjectReader gitObjectReader,
            WorkspaceGateway workspaceGateway
    ) {
        this.gitObjectReader = gitObjectReader;
        this.workspaceGateway = workspaceGateway;
    }

    @Autowired
    public ReadFileTool(
            GitObjectReader gitObjectReader,
            ObjectProvider<WorkspaceGateway> workspaceGatewayProvider
    ) {
        this(gitObjectReader, workspaceGatewayProvider.getIfAvailable());
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("revision").put("type", "string");
        properties.putObject("filePath")
                .put("type", "string")
                .put("maxLength", 4096);
        properties.putObject("startLine")
                .put("type", "integer")
                .put("minimum", 1);
        properties.putObject("endLine")
                .put("type", "integer")
                .put("minimum", 1);
        schema.putArray("required")
                .add("revision")
                .add("filePath")
                .add("startLine")
                .add("endLine");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "readFile",
                "Reads a bounded line range from BASE, TARGET, or the current WORKSPACE",
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
                    "revision must be BASE, TARGET, or WORKSPACE"
            );
        }

        if (revision == Revision.WORKSPACE) {
            return readWorkspace(execution, filePath, startLine, requestedEndLine);
        }

        String revisionSha;
        if (revision == Revision.BASE) {
            revisionSha = run.revisionScope().baseSha1().value();
        } else if (run.revisionScope() instanceof DiffScope diffScope) {
            revisionSha = diffScope.targetSha1().value();
        } else {
            return missingRevision(Revision.TARGET);
        }

        try {
            CommitObject commit = gitObjectReader.requireCommit(
                    run.repoKey(),
                    revisionSha
            );
            GitObjectId blobSha = commit.mapping().get(filePath);
            if (blobSha == null) {
                return ToolResult.error(
                        ToolStatus.NOT_FOUND,
                        "FILE_NOT_FOUND",
                        "File does not exist at the requested revision",
                        false
                );
            }
            byte[] content = readBoundedBlob(run.repoKey(), blobSha.value());
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
            int returnedBytes = 0;
            for (int lineNumber = startLine; lineNumber <= actualEndLine; lineNumber++) {
                String lineContent = allLines.get(lineNumber - 1);
                returnedBytes = Math.addExact(
                        returnedBytes,
                        lineContent.getBytes(StandardCharsets.UTF_8).length
                );
                if (returnedBytes > MAX_RETURNED_BYTES) {
                    return invalidArgument(
                            "READ_OUTPUT_TOO_LARGE",
                            "Requested line range exceeds the readFile output limit"
                    );
                }
                ObjectNode line = lines.addObject();
                line.put("lineNumber", lineNumber);
                line.put("content", lineContent);
            }
            return ToolResult.success(payload);
        } catch (BlobSizeLimitExceededException e) {
            return invalidArgument(
                    "FILE_TOO_LARGE",
                    "File exceeds the readFile size limit"
            );
        } catch (GitObjectReadException e) {
            return mapReadFailure(e);
        } catch (CharacterCodingException e) {
            return invalidArgument(
                    "UNSUPPORTED_FILE_ENCODING",
                    "readFile only supports valid UTF-8 text files"
            );
        } catch (IOException e) {
            return ToolResult.error(
                    ToolStatus.INTERNAL_ERROR,
                    "FILE_STREAM_COLLECTION_FAILED",
                    "Could not collect the bounded file stream",
                    false
            );
        }
    }

    private byte[] readBoundedBlob(String repoKey, String blobSha1)
            throws IOException {
        BoundedByteArrayOutputStream destination =
                new BoundedByteArrayOutputStream(MAX_FILE_BYTES);
        gitObjectReader.copyBlobTo(repoKey, blobSha1, destination);
        return destination.toByteArray();
    }

    private ToolResult missingRevision(Revision revision) {
        return switch (revision) {
            case BASE -> ToolResult.error(
                    ToolStatus.CONFLICT,
                    "BASE_REVISION_MISSING",
                    "Execution context does not contain a BASE revision",
                    false
            );
            case TARGET -> ToolResult.error(
                    ToolStatus.CONFLICT,
                    "TARGET_REVISION_MISSING",
                    "Execution context does not contain a TARGET revision",
                    false
            );
            case WORKSPACE -> throw new IllegalArgumentException(
                    "WORKSPACE does not use an immutable revision SHA"
            );
        };
    }

    private ToolResult readWorkspace(
            ToolExecutionContext execution,
            String filePath,
            int startLine,
            int endLine
    ) {
        if (workspaceGateway == null) {
            return ToolResult.error(
                    ToolStatus.INTERNAL_ERROR,
                    "WORKSPACE_GATEWAY_UNAVAILABLE",
                    "Workspace file access is not configured",
                    false
            );
        }
        try {
            WorkspaceGateway.FileContent content = workspaceGateway.readFile(
                    execution.requireWorkspaceId(),
                    filePath,
                    startLine,
                    endLine
            );
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("revision", Revision.WORKSPACE.name());
            payload.put("generation", content.generation());
            payload.put("filePath", content.filePath());
            payload.put("startLine", content.startLine());
            payload.put("endLine", content.endLine());
            payload.put("totalLines", content.totalLines());
            ArrayNode lines = payload.putArray("lines");
            for (WorkspaceGateway.FileLine fileLine : content.lines()) {
                ObjectNode line = lines.addObject();
                line.put("lineNumber", fileLine.lineNumber());
                line.put("content", fileLine.content());
            }
            return ToolResult.success(payload);
        } catch (IllegalStateException exception) {
            return WorkspaceToolResults.missingContext();
        } catch (WorkspaceOperationException exception) {
            return WorkspaceToolResults.error(exception);
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

    private static final class BoundedByteArrayOutputStream extends OutputStream {

        private final int maxBytes;
        private final ByteArrayOutputStream delegate;

        private BoundedByteArrayOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
            this.delegate = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void requireCapacity(int additionalBytes)
                throws BlobSizeLimitExceededException {
            if ((long) delegate.size() + additionalBytes > maxBytes) {
                throw new BlobSizeLimitExceededException();
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class BlobSizeLimitExceededException extends IOException {
    }
}
