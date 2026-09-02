package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.PatchOperationResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import com.gitnova.service.agent.workspace.SnapshotScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPatchToolTest {

    private static final String SHA_A = "a".repeat(64);
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.generate();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldUseTrustedWorkspaceAndBuildOrderedCommandFromModelArguments() {
        AtomicReference<WorkspaceId> capturedWorkspace = new AtomicReference<>();
        AtomicReference<WorkspaceMutationCommand> capturedCommand = new AtomicReference<>();
        WorkspaceGateway gateway = (workspaceId, executionPermit, command) -> {
            capturedWorkspace.set(workspaceId);
            capturedCommand.set(command);
            return PatchBatchResult.success(
                    command,
                    command.expectedGeneration(),
                    List.of(PatchOperationResult.applied(
                            command.operations().get(0),
                            null,
                            SHA_A
                    ))
            );
        };
        ApplyPatchTool tool = new ApplyPatchTool(gateway, objectMapper);

        ToolResult result = tool.execute(workspaceExecution(), createArguments(4));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(WORKSPACE_ID, capturedWorkspace.get());
        assertEquals(4, capturedCommand.get().expectedGeneration());
        assertEquals("src/New.java", capturedCommand.get().operations().get(0).filePath());
        assertEquals(5, result.payload().path("generationAfter").asLong());
        assertEquals(ToolAccessMode.WORKSPACE_WRITE, tool.accessMode());
        assertTrue(tool.concurrencySafe());
    }

    @Test
    void shouldRejectMissingWorkspaceMutationCapabilityWithoutInvokingGateway() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, executionPermit, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("gateway must not be invoked");
                },
                objectMapper
        );

        AgentRunContext run = run();
        AgentExecutionContext readOnly = new AgentExecutionContext(
                "session-read-only",
                run,
                1L,
                "Inspect without modifying",
                new WorkspaceBinding(WORKSPACE_ID),
                new WorkspaceExecutionPermit(run.runId(), WORKSPACE_ID, 1L),
                com.gitnova.service.agent.AgentTestExecutionConfigs.minimal(
                        Set.of(AgentCapability.CODE_READ)
                )
        );
        ToolResult result = new ToolRegistry(List.of(tool)).execute(
                new ToolExecutionContext(readOnly, 0, "call-1"),
                "applyPatch",
                createArguments(0)
        );

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("MISSING_TOOL_CAPABILITY", result.errorCode());
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldRejectInvalidNestedOperationWithoutInvokingGateway() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, executionPermit, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("gateway must not be invoked");
                },
                objectMapper
        );
        ObjectNode arguments = createArguments(0);
        ((ObjectNode) arguments.path("operations").get(0)).put("unexpected", true);

        ToolResult result = tool.execute(workspaceExecution(), arguments);

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("UNKNOWN_PATCH_OPERATION_FIELD", result.errorCode());
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldReturnStateBearingPartialToolResult() {
        WorkspaceGateway gateway = (workspaceId, executionPermit, command) -> PatchBatchResult.partialSuccess(
                command,
                command.expectedGeneration(),
                List.of(
                        PatchOperationResult.applied(
                                command.operations().get(0),
                                null,
                                SHA_A
                        ),
                        PatchOperationResult.failed(
                                command.operations().get(1),
                                null,
                                "FILE_NOT_FOUND",
                                "Target file does not exist"
                        )
                ),
                "PATCH_OPERATION_FAILED",
                "A confirmed operation was applied before a later operation failed"
        );
        ApplyPatchTool tool = new ApplyPatchTool(gateway, objectMapper);
        ObjectNode arguments = createArguments(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) arguments.path("operations"))
                .addObject()
                .put("type", "DELETE")
                .put("filePath", "missing.txt");

        ToolResult result = tool.execute(workspaceExecution(), arguments);

        assertEquals(ToolStatus.PARTIAL_SUCCESS, result.status());
        assertFalse(result.retryable());
        assertEquals(1, result.payload().path("generationAfter").asLong());
        assertEquals("APPLIED", result.payload().path("operationResults").get(0).path("status").asText());
        assertEquals("FAILED", result.payload().path("operationResults").get(1).path("status").asText());
    }

    @Test
    void shouldExposeNonApplyingUnifiedDiffAsAModelCorrectableConflict() {
        WorkspaceGateway gateway = (workspaceId, executionPermit, command) -> PatchBatchResult.failed(
                command,
                command.expectedGeneration(),
                List.of(PatchOperationResult.failed(
                        command.operations().get(0),
                        SHA_A,
                        "PATCH_DOES_NOT_APPLY",
                        "UPDATE patch does not apply to the current file content"
                )),
                "PATCH_OPERATION_FAILED",
                "The first workspace operation failed"
        );
        ApplyPatchTool tool = new ApplyPatchTool(gateway, objectMapper);

        ToolResult result = tool.execute(workspaceExecution(), createArguments(3));

        assertEquals(ToolStatus.CONFLICT, result.status());
        assertEquals("PATCH_DOES_NOT_APPLY", result.errorCode());
        assertFalse(result.retryable());
        assertEquals(3, result.payload().path("generationBefore").asLong());
        assertEquals(3, result.payload().path("generationAfter").asLong());
    }

    @Test
    void shouldRejectModelSuppliedWorkspaceIdentityAtRegistryBoundary() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, executionPermit, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("gateway must not be invoked");
                },
                objectMapper
        );
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ObjectNode arguments = createArguments(0);
        arguments.put("workspaceId", WorkspaceId.generate().toString());

        ToolResult result = registry.execute(
                workspaceExecution(),
                "applyPatch",
                arguments
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldAcceptEmptyCreateContentAndExactOperationByteLimit() {
        AtomicReference<WorkspaceMutationCommand> captured = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        WorkspaceGateway gateway = (workspaceId, executionPermit, command) -> {
            invocations.incrementAndGet();
            captured.set(command);
            return PatchBatchResult.conflict(
                    command,
                    command.expectedGeneration(),
                    "TEST_CONFLICT",
                    "Parsing reached the Workspace boundary"
            );
        };
        ApplyPatchTool tool = new ApplyPatchTool(gateway, objectMapper);

        ObjectNode empty = createArguments(0);
        ((ObjectNode) empty.path("operations").get(0)).put("content", "");
        ToolResult emptyResult = tool.execute(workspaceExecution(), empty);

        ObjectNode exactLimit = createArguments(0);
        ((ObjectNode) exactLimit.path("operations").get(0))
                .put("content", "x".repeat(1024 * 1024));
        ToolResult exactResult = tool.execute(workspaceExecution(), exactLimit);

        assertEquals(ToolStatus.CONFLICT, emptyResult.status());
        assertEquals(ToolStatus.CONFLICT, exactResult.status());
        assertEquals(2, invocations.get());
        assertEquals(1024 * 1024, captured.get().operations().get(0).content().length());
    }

    @Test
    void shouldRejectOperationBytesAndOperationCountAboveLimitsWithoutInvokingGateway() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, executionPermit, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("invalid input must not reach Workspace");
                },
                objectMapper
        );

        ObjectNode tooLarge = createArguments(0);
        ((ObjectNode) tooLarge.path("operations").get(0))
                .put("content", "x".repeat(1024 * 1024 + 1));
        ToolResult tooLargeResult = tool.execute(workspaceExecution(), tooLarge);

        ObjectNode tooMany = createArguments(0);
        var operations = (com.fasterxml.jackson.databind.node.ArrayNode) tooMany.path("operations");
        for (int index = 1; index <= 32; index++) {
            operations.addObject()
                    .put("type", "CREATE")
                    .put("filePath", "src/File" + index + ".java")
                    .put("content", "");
        }
        ToolResult tooManyResult = tool.execute(workspaceExecution(), tooMany);

        assertEquals(ToolStatus.INVALID_ARGUMENT, tooLargeResult.status());
        assertEquals("PATCH_OPERATION_TOO_LARGE", tooLargeResult.errorCode());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tooManyResult.status());
        assertEquals("INVALID_PATCH_OPERATION_COUNT", tooManyResult.errorCode());
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldAcceptExactlyMaximumOperationCount() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, executionPermit, command) -> {
                    invocations.incrementAndGet();
                    return PatchBatchResult.conflict(
                            command,
                            command.expectedGeneration(),
                            "TEST_CONFLICT",
                            "Parsing reached the Workspace boundary"
                    );
                },
                objectMapper
        );
        ObjectNode arguments = createArguments(0);
        var operations = (com.fasterxml.jackson.databind.node.ArrayNode) arguments.path("operations");
        for (int index = 1; index < 32; index++) {
            operations.addObject()
                    .put("type", "CREATE")
                    .put("filePath", "src/File" + index + ".java")
                    .put("content", "");
        }

        ToolResult result = tool.execute(workspaceExecution(), arguments);

        assertEquals(ToolStatus.CONFLICT, result.status());
        assertEquals(1, invocations.get());
    }

    private ObjectNode createArguments(long expectedGeneration) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("expectedGeneration", expectedGeneration);
        arguments.putArray("operations")
                .addObject()
                .put("type", "CREATE")
                .put("filePath", "src/New.java")
                .put("content", "class New {}\n");
        return arguments;
    }

    private ToolExecutionContext workspaceExecution() {
        return com.gitnova.service.agent.AgentTestContexts.workspaceToolExecution(
                run(),
                0,
                "call-1",
                WORKSPACE_ID
        );
    }

    private AgentRunContext run() {
        return new AgentRunContext(
                "context-1",
                10L,
                "1/10",
                SnapshotScope.of("a".repeat(40))
        );
    }
}
