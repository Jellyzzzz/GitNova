package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import com.gitnova.service.agent.workspace.SnapshotScope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingWorkspaceToolsTest {

    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.generate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkspaceGateway gateway = new FakeGateway();

    @Test
    void shouldExecuteAllReadAndValidationToolsThroughCapabilityRegistry() {
        List<AgentTool> tools = List.of(
                new ListFilesTool(gateway, objectMapper),
                new FindFilesTool(gateway, objectMapper),
                new SearchTextTool(gateway, objectMapper),
                new ReadFileTool(unusedObjectReader(), gateway),
                new GetWorkspaceDiffTool(gateway, objectMapper),
                new RunCommandTool(gateway, objectMapper),
                new FinishTaskTool(objectMapper)
        );
        ToolRegistry registry = new ToolRegistry(tools);

        ToolResult listed = registry.execute(
                execution("call-list"),
                "listFiles",
                objectMapper.createObjectNode().put("path", ".")
        );
        ToolResult found = registry.execute(
                execution("call-find"),
                "findFiles",
                objectMapper.createObjectNode().put("glob", "**/*.java")
        );
        ObjectNode searchArguments = objectMapper.createObjectNode();
        searchArguments.put("query", "Main");
        searchArguments.put("caseSensitive", true);
        ToolResult searched = registry.execute(
                execution("call-search"),
                "searchText",
                searchArguments
        );
        ObjectNode readArguments = objectMapper.createObjectNode();
        readArguments.put("revision", "WORKSPACE");
        readArguments.put("filePath", "src/Main.java");
        readArguments.put("startLine", 1);
        readArguments.put("endLine", 10);
        ToolResult read = registry.execute(
                execution("call-read"),
                "readFile",
                readArguments
        );
        ToolResult diff = registry.execute(
                execution("call-diff"),
                "getWorkspaceDiff",
                objectMapper.createObjectNode()
        );
        ToolResult command = registry.execute(
                execution("call-command"),
                "runCommand",
                commandArguments()
        );
        ToolResult finalized = registry.execute(
                execution("call-finalize"),
                FinishTaskTool.NAME,
                finalizeArguments()
        );

        assertEquals(7, registry.definitions(AgentCapabilityPolicy.cloudAgent()).size());
        assertEquals(ToolStatus.SUCCESS, listed.status());
        assertEquals("src/Main.java", found.payload().path("paths").get(0).asText());
        assertEquals(2, searched.payload().path("matches").get(0).path("lineNumber").asInt());
        assertTrue(searched.truncated());
        assertEquals("WORKSPACE", read.payload().path("revision").asText());
        assertEquals(4, read.payload().path("generation").asLong());
        assertEquals(1, diff.payload().path("files").size());
        assertEquals(ToolStatus.SUCCESS, command.status());
        assertEquals(1, command.payload().path("exitCode").asInt());
        assertEquals(ToolStatus.SUCCESS, finalized.status());
        assertEquals(4, finalized.payload().path("expectedGeneration").asLong());
        assertTrue(registry.isTerminal(FinishTaskTool.NAME));
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
    void shouldRejectInvalidCompletionDraftAndNotTreatClaimsAsVerifiedFacts() {
        FinishTaskTool tool = new FinishTaskTool(objectMapper);
        ObjectNode invalid = finalizeArguments();
        ((ArrayNode) invalid.path("agentModifiedFiles")).add("../escape.java");

        ToolResult result = tool.execute(execution("call-finalize"), invalid);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("INVALID_COMPLETION_DRAFT", result.errorCode());
        assertFalse(result.retryable());
        assertTrue(tool.terminal());
    }

    @Test
    void shouldRejectCommandInputsOutsideGatewayContractLimits() {
        WorkspaceGateway.CommandRequest exactBoundary = new WorkspaceGateway.CommandRequest(
                4,
                java.util.Collections.nCopies(
                        WorkspaceGateway.MAX_COMMAND_ARG_COUNT,
                        "argument"
                ),
                ".",
                WorkspaceGateway.MAX_COMMAND_TIMEOUT_SECONDS,
                "exact boundary"
        );
        assertEquals(WorkspaceGateway.MAX_COMMAND_ARG_COUNT, exactBoundary.argv().size());
        assertEquals(
                WorkspaceGateway.MAX_COMMAND_TIMEOUT_SECONDS,
                exactBoundary.timeoutSeconds()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceGateway.CommandRequest(
                        4,
                        List.of(),
                        ".",
                        30,
                        "empty command"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceGateway.CommandRequest(
                        4,
                        java.util.Collections.nCopies(
                                WorkspaceGateway.MAX_COMMAND_ARG_COUNT + 1,
                                "argument"
                        ),
                        ".",
                        30,
                        "too many arguments"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceGateway.CommandRequest(
                        4,
                        List.of("test-runner"),
                        ".",
                        WorkspaceGateway.MAX_COMMAND_TIMEOUT_SECONDS + 1,
                        "timeout too large"
                )
        );
    }

    @Test
    void shouldRejectEmptyCommandBeforeInvokingWorkspace() {
        AtomicInteger invocations = new AtomicInteger();
        WorkspaceGateway recordingGateway = new WorkspaceGateway() {
            @Override
            public PatchBatchResult applyPatch(
                    WorkspaceId workspaceId,
                    WorkspaceExecutionPermit executionPermit,
                    WorkspaceMutationCommand command
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CommandResult runCommand(
                    WorkspaceId workspaceId,
                    WorkspaceExecutionPermit executionPermit,
                    CommandRequest request
            ) {
                invocations.incrementAndGet();
                throw new AssertionError("empty command must not execute");
            }
        };
        RunCommandTool tool = new RunCommandTool(recordingGateway, objectMapper);
        ObjectNode arguments = commandArguments();
        arguments.putArray("argv");

        ToolResult result = tool.execute(execution("call-empty-command"), arguments);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("INVALID_COMMAND_ARGV", result.errorCode());
        assertEquals(0, invocations.get());
    }

    private ObjectNode commandArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 4);
        arguments.putArray("argv").add("./mvnw").add("test");
        arguments.put("workingDirectory", ".");
        arguments.put("timeoutSeconds", 120);
        arguments.put("purpose", "context tests");
        return arguments;
    }

    private ObjectNode finalizeArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", 4);
        arguments.put("summary", "Updated Main and ran focused tests");
        arguments.putArray("findings");
        arguments.putArray("agentModifiedFiles").add("src/Main.java");
        ObjectNode validation = arguments.putArray("claimedValidations").addObject();
        validation.putArray("argv").add("./mvnw").add("test");
        validation.put("result", "FAILED");
        arguments.putArray("risks").add("Test fixture still fails");
        arguments.putArray("followUps").add("Investigate the failure");
        return arguments;
    }

    private ToolExecutionContext execution(String callId) {
        return com.gitnova.service.agent.AgentTestContexts.workspaceToolExecution(
                new AgentRunContext(
                        "context-1",
                        10L,
                        "1/10",
                        SnapshotScope.of("a".repeat(40))
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
                WorkspaceExecutionPermit executionPermit,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileListing listFiles(WorkspaceId workspaceId, String directory) {
            return new FileListing(
                    4,
                    directory,
                    List.of(new FileEntry("src", FileType.DIRECTORY, 0)),
                    false
            );
        }

        @Override
        public FileSearch findFiles(WorkspaceId workspaceId, String glob) {
            return new FileSearch(4, glob, List.of("src/Main.java"), false);
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
                    List.of(new TextMatch("src/Main.java", 2, "class Main {}")),
                    true
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
        public CommandResult runCommand(
                WorkspaceId workspaceId,
                WorkspaceExecutionPermit executionPermit,
                CommandRequest request
        ) {
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
                        false,
                        false,
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
                    false,
                    false,
                    null,
                    null
            );
        }
    }
}
