package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingWorkspaceToolsTest {

    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.generate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkspaceGateway gateway = new FakeGateway();

    @Test
    void shouldExecuteAllReadAndValidationToolsThroughScopedRegistry() {
        List<AgentTool> tools = List.of(
                new ListFilesTool(gateway, objectMapper),
                new FindFilesTool(gateway, objectMapper),
                new SearchTextTool(gateway, objectMapper),
                new ReadFileTool(unusedObjectReader(), gateway),
                new GetWorkspaceDiffTool(gateway, objectMapper),
                new RunCommandTool(gateway, objectMapper),
                new FinalizeTaskTool(objectMapper)
        );
        ToolRegistry registry = new ToolRegistry(tools);
        Set<String> allowed = Set.of(
                "listFiles",
                "findFiles",
                "searchText",
                "readFile",
                "getWorkspaceDiff",
                "runCommand",
                "finalizeTask"
        );

        ToolResult listed = registry.executeScoped(
                execution("call-list"),
                allowed,
                "listFiles",
                objectMapper.createObjectNode().put("path", ".")
        );
        ToolResult found = registry.executeScoped(
                execution("call-find"),
                allowed,
                "findFiles",
                objectMapper.createObjectNode().put("glob", "**/*.java")
        );
        ObjectNode searchArguments = objectMapper.createObjectNode();
        searchArguments.put("query", "Main");
        searchArguments.put("caseSensitive", true);
        ToolResult searched = registry.executeScoped(
                execution("call-search"),
                allowed,
                "searchText",
                searchArguments
        );
        ObjectNode readArguments = objectMapper.createObjectNode();
        readArguments.put("revision", "WORKSPACE");
        readArguments.put("filePath", "src/Main.java");
        readArguments.put("startLine", 1);
        readArguments.put("endLine", 10);
        ToolResult read = registry.executeScoped(
                execution("call-read"),
                allowed,
                "readFile",
                readArguments
        );
        ToolResult diff = registry.executeScoped(
                execution("call-diff"),
                allowed,
                "getWorkspaceDiff",
                objectMapper.createObjectNode()
        );
        ToolResult command = registry.executeScoped(
                execution("call-command"),
                allowed,
                "runCommand",
                commandArguments()
        );
        ToolResult finalized = registry.executeScoped(
                execution("call-finalize"),
                allowed,
                "finalizeTask",
                finalizeArguments()
        );

        assertEquals(7, registry.definitions(allowed).size());
        assertEquals(ToolStatus.SUCCESS, listed.status());
        assertEquals("src/Main.java", found.payload().path("paths").get(0).asText());
        assertEquals(2, searched.payload().path("matches").get(0).path("lineNumber").asInt());
        assertEquals("WORKSPACE", read.payload().path("revision").asText());
        assertEquals(4, read.payload().path("generation").asLong());
        assertEquals(1, diff.payload().path("files").size());
        assertEquals(ToolStatus.SUCCESS, command.status());
        assertEquals(1, command.payload().path("exitCode").asInt());
        assertEquals(ToolStatus.SUCCESS, finalized.status());
        assertEquals(4, finalized.payload().path("expectedGeneration").asLong());
        assertTrue(registry.isTerminal("finalizeTask"));
    }

    @Test
    void shouldKeepRunCommandFailureAsPayloadAndRejectStaleCommandAsConflict() {
        RunCommandTool tool = new RunCommandTool(gateway, objectMapper);

        ToolResult failedTests = tool.execute(execution("call-1"), commandArguments());
        ObjectNode staleArguments = commandArguments();
        staleArguments.put("expectedGeneration", 3);
        ToolResult stale = tool.execute(execution("call-2"), staleArguments);

        assertEquals(ToolStatus.SUCCESS, failedTests.status());
        assertEquals(1, failedTests.payload().path("exitCode").asInt());
        assertEquals(ToolStatus.CONFLICT, stale.status());
        assertEquals("STALE_WORKSPACE_GENERATION", stale.errorCode());
        assertEquals(ToolAccessMode.WORKSPACE_WRITE, tool.accessMode());
    }

    @Test
    void shouldRejectInvalidCodingDraftAndNotTreatClaimsAsVerifiedFacts() {
        FinalizeTaskTool tool = new FinalizeTaskTool(objectMapper);
        ObjectNode invalid = finalizeArguments();
        ((ArrayNode) invalid.path("changedFiles")).add("../escape.java");

        ToolResult result = tool.execute(execution("call-finalize"), invalid);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("INVALID_CODING_DRAFT", result.errorCode());
        assertFalse(result.retryable());
        assertTrue(tool.terminal());
    }

    private ObjectNode commandArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 4);
        arguments.putArray("argv").add("./mvnw").add("test");
        arguments.put("workingDirectory", ".");
        arguments.put("timeoutSeconds", 120);
        arguments.put("purpose", "run tests");
        return arguments;
    }

    private ObjectNode finalizeArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 4);
        arguments.put("summary", "Updated Main and ran focused tests");
        arguments.putArray("changedFiles").add("src/Main.java");
        ObjectNode validation = arguments.putArray("validations").addObject();
        validation.putArray("argv").add("./mvnw").add("test");
        validation.put("result", "FAILED");
        arguments.putArray("risks").add("Test fixture still fails");
        arguments.putArray("followUps").add("Investigate the failure");
        return arguments;
    }

    private ToolExecutionContext execution(String callId) {
        return ToolExecutionContext.forWorkspace(
                new AgentRunContext(
                        "run-1",
                        10L,
                        "1/10",
                        "a".repeat(40),
                        "b".repeat(40)
                ),
                0,
                callId,
                WORKSPACE_ID
        );
    }

    private GitObjectReader unusedObjectReader() {
        return new GitObjectReader() {
            @Override
            public CommitObject requireCommit(String repoKey, String sha1) {
                throw new AssertionError("immutable snapshot reader must not be used");
            }

            @Override
            public byte[] requireBlob(String repoKey, String sha1) {
                throw new AssertionError("immutable snapshot reader must not be used");
            }

            @Override
            public long copyBlobTo(
                    String repoKey,
                    String sha1,
                    OutputStream destination
            ) throws IOException {
                throw new AssertionError("immutable snapshot reader must not be used");
            }
        };
    }

    private static final class FakeGateway implements WorkspaceGateway {

        @Override
        public PatchBatchResult applyPatch(
                WorkspaceId workspaceId,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileListing listFiles(WorkspaceId workspaceId, String directory) {
            return new FileListing(
                    4,
                    directory,
                    List.of(new FileEntry("src", FileType.DIRECTORY, 0))
            );
        }

        @Override
        public FileSearch findFiles(WorkspaceId workspaceId, String glob) {
            return new FileSearch(4, glob, List.of("src/Main.java"));
        }

        @Override
        public TextSearch searchText(
                WorkspaceId workspaceId,
                String query,
                boolean caseSensitive
        ) {
            return new TextSearch(
                    4,
                    query,
                    caseSensitive,
                    List.of(new TextMatch("src/Main.java", 2, "class Main {}"))
            );
        }

        @Override
        public FileContent readFile(
                WorkspaceId workspaceId,
                String filePath,
                int startLine,
                int endLine
        ) {
            return new FileContent(
                    4,
                    filePath,
                    1,
                    1,
                    1,
                    List.of(new FileLine(1, "class Main {}"))
            );
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return new WorkspaceDiff(
                    4,
                    List.of(new DiffFile(
                            "src/Main.java",
                            DiffChangeType.MODIFIED,
                            1,
                            1,
                            1,
                            false
                    )),
                    1,
                    1,
                    1,
                    false,
                    "@@ -1 +1 @@\n-old\n+new\n"
            );
        }

        @Override
        public CommandResult runCommand(WorkspaceId workspaceId, CommandRequest request) {
            if (request.expectedGeneration() != 4) {
                return new CommandResult(
                        CommandStatus.CONFLICT,
                        request.expectedGeneration(),
                        4,
                        4,
                        null,
                        0,
                        "",
                        "",
                        "STALE_WORKSPACE_GENERATION",
                        "Expected generation is stale"
                );
            }
            return new CommandResult(
                    CommandStatus.COMPLETED,
                    4,
                    4,
                    4,
                    1,
                    25,
                    "",
                    "test failed",
                    null,
                    null
            );
        }
    }
}
