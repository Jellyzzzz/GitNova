package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolAccessMode;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.PatchOperationResult;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
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
        WorkspaceGateway gateway = (workspaceId, command) -> {
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
    void shouldRejectMissingTrustedWorkspaceWithoutInvokingGateway() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("gateway must not be invoked");
                },
                objectMapper
        );

        ToolResult result = tool.execute(
                new ToolExecutionContext(run(), 0, "call-1"),
                createArguments(0)
        );

        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("WORKSPACE_CONTEXT_REQUIRED", result.errorCode());
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldRejectInvalidNestedOperationWithoutInvokingGateway() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, command) -> {
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
        WorkspaceGateway gateway = (workspaceId, command) -> PatchBatchResult.partialSuccess(
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
    void shouldRejectModelSuppliedWorkspaceIdentityAtRegistryBoundary() {
        AtomicInteger invocations = new AtomicInteger();
        ApplyPatchTool tool = new ApplyPatchTool(
                (workspaceId, command) -> {
                    invocations.incrementAndGet();
                    throw new AssertionError("gateway must not be invoked");
                },
                objectMapper
        );
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ObjectNode arguments = createArguments(0);
        arguments.put("workspaceId", WorkspaceId.generate().toString());

        ToolResult result = registry.executeScoped(
                workspaceExecution(),
                Set.of("applyPatch"),
                "applyPatch",
                arguments
        );

        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        assertEquals(0, invocations.get());
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
        return ToolExecutionContext.forWorkspace(
                run(),
                0,
                "call-1",
                WORKSPACE_ID
        );
    }

    private AgentRunContext run() {
        return new AgentRunContext(
                "run-1",
                10L,
                "1/10",
                "a".repeat(40),
                "b".repeat(40)
        );
    }
}
