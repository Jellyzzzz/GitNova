package com.gitnova.service.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.service.agent.AgentTestContexts;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.execution.AgentExecutionPersistenceException;
import com.gitnova.service.agent.runtime.AgentCapability;
import com.gitnova.service.agent.runtime.AgentCapabilityPolicy;
import com.gitnova.service.agent.runtime.AgentExecutionContext;
import com.gitnova.service.agent.runtime.AgentRunContext;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final ToolExecutionContext execution = AgentTestContexts.workspaceToolExecution(
            new AgentRunContext("run-edit", 10L, "1/10", SnapshotScope.of("a".repeat(40))), 0, "call-edit", workspaceId);

    @Test
    void shouldDispatchOneEditOperationWithTrustedWorkspaceAndPermit() {
        AtomicReference<WorkspaceMutationCommand> received = new AtomicReference<>();
        WorkspaceGateway gateway = (id, permit, command) -> {
            assertEquals(workspaceId, id);
            assertEquals(execution.requireExecutionPermit(), permit);
            received.set(command);
            return PatchBatchResult.success(command, 4, List.of(PatchOperationResult.applied(
                    command.operations().get(0), "a".repeat(64), "b".repeat(64))));
        };
        var tool = new EditFileTool(gateway, mapper);
        var result = new ToolRegistry(List.of(tool)).execute(execution, "edit", arguments(4, "old", ""));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(1, received.get().operations().size());
        assertEquals(PatchOperationType.EDIT, received.get().operations().get(0).type());
        assertEquals("", received.get().operations().get(0).edits().get(0).newText());
        assertEquals(5, result.payload().path("generationAfter").asLong());
        assertEquals(1, result.payload().path("replacementCount").asInt());
        assertEquals("file.txt", result.payload().path("filePath").asText());
        assertTrue(tool.concurrencySafe());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"\",\"newText\":\"x\"}]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"x\",\"newText\":null}]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"x\",\"newText\":7}]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[null]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"x\",\"newText\":\"y\",\"other\":true}]}",
            "{\"expectedGeneration\":0,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"x\",\"newText\":\"y\"}],\"workspaceId\":\"untrusted\"}",
            "{\"expectedGeneration\":9223372036854775808,\"filePath\":\"file.txt\",\"edits\":[{\"oldText\":\"x\",\"newText\":\"y\"}]}"
    })
    void shouldRejectMalformedArgumentsWithoutReachingWorkspace(String json) throws Exception {
        var tool = new EditFileTool((id, permit, command) -> { throw new AssertionError("must not execute"); }, mapper);
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(execution, mapper.readTree(json)).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT,
                new ToolRegistry(List.of(tool)).execute(execution, "edit", mapper.readTree(json)).status());
    }

    @Test
    void shouldEnforceUtf8ByteLimitAndReplacementCount() {
        var tool = new EditFileTool((id, permit, command) -> PatchBatchResult.conflict(command, 0, "TEST", "parsed"), mapper);
        assertEquals(ToolStatus.CONFLICT, tool.execute(execution, arguments(0, "x", "a".repeat(PatchOperation.MAX_EDIT_TEXT_BYTES))).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(execution, arguments(0, "x", "a".repeat(PatchOperation.MAX_EDIT_TEXT_BYTES + 1))).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(execution, arguments(0, "x", "中".repeat(PatchOperation.MAX_EDIT_TEXT_BYTES / 3 + 1))).status());

        ObjectNode args = arguments(0, "old-0", "new");
        var edits = (com.fasterxml.jackson.databind.node.ArrayNode) args.path("edits");
        for (int i = 1; i < PatchOperation.MAX_EDITS; i++) edits.addObject().put("oldText", "old-" + i).put("newText", "new");
        assertEquals(ToolStatus.CONFLICT, tool.execute(execution, args).status());
        edits.addObject().put("oldText", "one-too-many").put("newText", "new");
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(execution, args).status());
    }

    @Test
    void shouldRejectMissingMutationCapabilityAndExcludeEditFromReadOnlyDefinitions() {
        var tool = new EditFileTool((id, permit, command) -> { throw new AssertionError("must not execute"); }, mapper);
        var registry = new ToolRegistry(List.of(tool));
        var source = execution.agent();
        var readOnly = new AgentExecutionContext(source.sessionId(), source.context(), source.actorId(), source.taskText(),
                source.workspace(), source.executionPermit(), AgentTestExecutionConfigs.minimal(Set.of(AgentCapability.CODE_READ)));
        var result = registry.execute(new ToolExecutionContext(readOnly, 0, "read-only"), "edit", arguments(0, "old", "new"));
        assertEquals(ToolStatus.PERMISSION_DENIED, result.status());
        assertEquals("MISSING_TOOL_CAPABILITY", result.errorCode());
        assertTrue(registry.definitions(new AgentCapabilityPolicy(Set.of(AgentCapability.CODE_READ))).isEmpty());
    }

    @Test
    void shouldExposeCorrectableFailureWithCurrentStateAndIndex() {
        var tool = new EditFileTool((id, permit, command) -> PatchBatchResult.failed(command, 4,
                List.of(PatchOperationResult.failed(command.operations().get(0), "a".repeat(64),
                        "EDIT_TEXT_AMBIGUOUS", "edits[1].oldText matches more than once; include more context")),
                "PATCH_OPERATION_FAILED", "The first operation failed"), mapper);
        var result = tool.execute(execution, arguments(4, "x", "y"));
        assertEquals(ToolStatus.CONFLICT, result.status());
        assertEquals("EDIT_TEXT_AMBIGUOUS", result.errorCode());
        assertTrue(result.message().contains("edits[1]"));
        assertFalse(result.retryable());
        assertEquals(4, result.payload().path("generationAfter").asLong());
        assertEquals(0, result.payload().path("replacementCount").asInt());
    }

    @Test
    void shouldPropagatePersistenceFailureInsteadOfMisreportingAnInvalidEdit() {
        var failure = new AgentExecutionPersistenceException(AgentExecutionPersistenceException.Code.PERSISTENCE_FAILURE, "write may exist");
        var tool = new EditFileTool((id, permit, command) -> { throw failure; }, mapper);
        assertSame(failure, assertThrows(AgentExecutionPersistenceException.class,
                () -> new ToolRegistry(List.of(tool)).execute(execution, "edit", arguments(0, "old", "new"))));
    }

    @Test
    void shouldKeepEditOutOfPreviouslyFrozenToolSets() {
        WorkspaceGateway gateway = (id, permit, command) -> { throw new AssertionError("not invoked"); };
        var patch = new ApplyPatchTool(gateway, mapper);
        var finish = new FinishTaskTool(mapper);
        var edit = new EditFileTool(gateway, mapper);
        var oldConfig = AgentTestExecutionConfigs.forTools(List.of(patch, finish), AgentTestExecutionConfigs.defaultPolicy());
        var registry = new ToolRegistry(List.of(patch, edit, finish));
        var resolver = AgentTestExecutionConfigs.resolver(registry);
        assertTrue(resolver.resolve(oldConfig.toolSet(), oldConfig.capabilityPolicy()).stream().noneMatch(def -> def.name().equals("edit")));
        var newConfig = AgentTestExecutionConfigs.forRegistry(registry, AgentTestExecutionConfigs.defaultPolicy());
        assertTrue(resolver.resolve(newConfig.toolSet(), newConfig.capabilityPolicy()).stream().anyMatch(def -> def.name().equals("edit")));
    }

    @Test
    void shouldNotExposeInternalEditOperationThroughApplyPatch() {
        var patch = new ApplyPatchTool((id, permit, command) -> { throw new AssertionError("must not execute"); }, mapper);
        ObjectNode args = mapper.createObjectNode().put("expectedGeneration", 0);
        args.putArray("operations").addObject().put("type", "EDIT").put("filePath", "file.txt");
        assertEquals("INVALID_PATCH_OPERATION_TYPE", patch.execute(execution, args).errorCode());
        // Registry dispatch rejects the nested enum before entering the tool parser.
        var result = new ToolRegistry(List.of(patch)).execute(execution, "applyPatch", args);
        assertEquals(ToolStatus.INVALID_ARGUMENT, result.status());
        assertEquals("SCHEMA_VALIDATION_FAILED", result.errorCode());
        assertEquals("NOT_STARTED", result.payload().path("executionState").asText());
        assertEquals(1, result.payload().path("violations").size());
        assertEquals("/operations/0/type", result.payload().path("violations").path(0).path("path").asText());
        assertEquals("ENUM_MISMATCH", result.payload().path("violations").path(0).path("code").asText());
    }

    private ObjectNode arguments(long generation, String oldText, String newText) {
        ObjectNode args = mapper.createObjectNode().put("expectedGeneration", generation).put("filePath", "file.txt");
        args.putArray("edits").addObject().put("oldText", oldText).put("newText", newText);
        return args;
    }
}
