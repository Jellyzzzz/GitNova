package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.dto.ToolDefinition;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.FakeModelGateway;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolExecutionContext;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolStatus;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import com.gitnova.service.agent.workspace.WorkspaceOperationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeWorkspaceDriftTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentExecutionConfig executionConfig;
    private final WorkspaceId workspaceId = WorkspaceId.generate();

    @Test
    void shouldReconcileDriftDuringModelCallBeforeToolExecutionAndNotifyNextTurn() {
        DriftingWorkspace workspace = new DriftingWorkspace();
        RecordingReadTool readTool = new RecordingReadTool();
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-read",
                        new ToolCall(
                                "call-read",
                                readTool.definition().name(),
                                objectMapper.createObjectNode()
                        )
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        new ToolCall(
                                "call-finish",
                                FinishTaskTool.NAME,
                                finishArguments(1)
                        )
                ));

        AgentRunResult result = runtime(
                modelGateway,
                workspace,
                List.of(readTool, new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(1, readTool.invocations);
        assertEquals(1, workspace.generation);

        List<ModelMessage> messages = modelGateway.receivedRequests().get(1).messages();
        assertEquals(ModelRole.ASSISTANT, messages.get(messages.size() - 3).role());
        assertEquals(ModelRole.TOOL, messages.get(messages.size() - 2).role());
        assertEquals("call-read", messages.get(messages.size() - 2).toolCallId());
        ModelMessage driftFeedback = messages.get(messages.size() - 1);
        assertEquals(ModelRole.USER, driftFeedback.role());
        assertTrue(driftFeedback.content().contains("authoritative at generation 1"));
        assertTrue(driftFeedback.content().contains("earlier generation is stale"));
    }

    @Test
    void shouldFailBeforeCallingModelWhenWorkspaceCannotBeSynchronized() {
        WorkspaceGateway unavailable = new WorkspaceGateway() {
            @Override
            public PatchBatchResult applyPatch(
                    WorkspaceId ignored,
                    WorkspaceExecutionPermit executionPermit,
                    WorkspaceMutationCommand command
            ) {
                throw new AssertionError("mutation must not execute");
            }

            @Override
            public WorkspaceRefresh refreshWorkspace(WorkspaceId ignored) {
                throw new WorkspaceOperationException(
                        WorkspaceOperationException.Reason.WORKSPACE_UNAVAILABLE,
                        "WORKSPACE_ROOT_UNAVAILABLE",
                        "Workspace root was deleted"
                );
            }
        };
        FakeModelGateway modelGateway = new FakeModelGateway();

        AgentRunResult result = runtime(
                modelGateway,
                unavailable,
                List.of(new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals(
                AgentTerminationReason.WORKSPACE_SYNC_FAILURE,
                result.terminationReason()
        );
        assertEquals(0, result.modelCallCount());
        assertTrue(modelGateway.receivedRequests().isEmpty());
    }

    @Test
    void shouldTerminatePartialWhenWorkspaceDisappearsAfterSuccessfulTool() {
        RecordingReadTool readTool = new RecordingReadTool();
        WorkspaceGateway disappearsAfterTool = new WorkspaceGateway() {
            private int refreshCalls;

            @Override
            public PatchBatchResult applyPatch(
                    WorkspaceId ignored,
                    WorkspaceExecutionPermit executionPermit,
                    WorkspaceMutationCommand command
            ) {
                throw new AssertionError("mutation must not execute");
            }

            @Override
            public WorkspaceRefresh refreshWorkspace(WorkspaceId ignored) {
                refreshCalls++;
                if (refreshCalls >= 3) {
                    throw new WorkspaceOperationException(
                            WorkspaceOperationException.Reason.WORKSPACE_UNAVAILABLE,
                            "WORKSPACE_ROOT_UNAVAILABLE",
                            "Workspace root disappeared after the tool"
                    );
                }
                return new WorkspaceRefresh(0, 0, false);
            }
        };
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-read",
                        new ToolCall(
                                "call-read",
                                readTool.definition().name(),
                                objectMapper.createObjectNode()
                        )
                ));

        AgentRunResult result = runtime(
                modelGateway,
                disappearsAfterTool,
                List.of(readTool, new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.PARTIAL, result.status());
        assertEquals(
                AgentTerminationReason.WORKSPACE_SYNC_FAILURE,
                result.terminationReason()
        );
        assertEquals(1, result.modelCallCount());
        assertEquals(1, result.toolCallCount());
        assertEquals(1, readTool.invocations);
        assertEquals(1, modelGateway.receivedRequests().size());
    }

    @Test
    void shouldRefreshBetweenToolCallsAndExposeTheLatestGeneration() throws Exception {
        BatchDriftingWorkspace workspace = new BatchDriftingWorkspace();
        ObservingTool first = new ObservingTool(
                "firstRead",
                workspace,
                workspace::markExternalChange
        );
        ObservingTool second = new ObservingTool(
                "secondRead",
                workspace,
                () -> { }
        );
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-batch",
                        call("call-first", first.definition().name(), objectMapper.createObjectNode()),
                        call("call-second", second.definition().name(), objectMapper.createObjectNode())
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        call("call-finish", FinishTaskTool.NAME, finishArguments(1))
                ));

        AgentRunResult result = runtime(
                modelGateway,
                workspace,
                List.of(first, second, new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(List.of(0L), first.observedGenerations);
        assertEquals(List.of(1L), second.observedGenerations);
        assertEquals(1, workspace.generation);

        List<ModelMessage> messages = modelGateway.receivedRequests().get(1).messages();
        int assistantIndex = messages.size() - 4;
        assertEquals(ModelRole.ASSISTANT, messages.get(assistantIndex).role());
        assertEquals(ModelRole.TOOL, messages.get(assistantIndex + 1).role());
        assertEquals("call-first", messages.get(assistantIndex + 1).toolCallId());
        assertEquals(ModelRole.TOOL, messages.get(assistantIndex + 2).role());
        assertEquals("call-second", messages.get(assistantIndex + 2).toolCallId());
        assertEquals(ModelRole.USER, messages.get(assistantIndex + 3).role());
        assertTrue(messages.get(assistantIndex + 3).content()
                .contains("authoritative at generation 1"));
    }

    @Test
    void shouldRejectAStaleWriteAfterAnExternalChangeBetweenToolCalls() throws Exception {
        BatchDriftingWorkspace workspace = new BatchDriftingWorkspace();
        ObservingTool first = new ObservingTool(
                "observeBeforeExternalEdit",
                workspace,
                workspace::markExternalChange
        );
        ExpectedGenerationWriteTool staleWrite = new ExpectedGenerationWriteTool(workspace);
        ObjectNode staleArguments = objectMapper.createObjectNode();
        staleArguments.put("expectedGeneration", 0);
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-stale-write",
                        call("call-observe", first.definition().name(), objectMapper.createObjectNode()),
                        call("call-stale-write", staleWrite.definition().name(), staleArguments)
                ))
                .enqueueResponse(toolResponse(
                        "response-finish",
                        call("call-finish", FinishTaskTool.NAME, finishArguments(1))
                ));

        AgentRunResult result = runtime(
                modelGateway,
                workspace,
                List.of(first, staleWrite, new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(1, staleWrite.invocations);
        assertFalse(staleWrite.mutated);
        assertEquals(1, workspace.generation);

        ModelMessage staleObservation = modelGateway.receivedRequests().get(1).messages()
                .stream()
                .filter(message -> "call-stale-write".equals(message.toolCallId()))
                .findFirst()
                .orElseThrow();
        ObjectNode resultNode = (ObjectNode) objectMapper.readTree(staleObservation.content());
        assertEquals(ToolStatus.CONFLICT.name(), resultNode.path("status").asText());
        assertEquals("STALE_WORKSPACE_GENERATION", resultNode.path("errorCode").asText());
    }

    @Test
    void shouldRejectEveryRemainingCallWhenSynchronizationFailsInsideABatch() {
        FailingBatchWorkspace workspace = new FailingBatchWorkspace(3);
        ObservingTool first = new ObservingTool("first", workspace, () -> { });
        ObservingTool second = new ObservingTool("second", workspace, () -> { });
        ObservingTool third = new ObservingTool("third", workspace, () -> { });
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-sync-failure",
                        call("call-first", first.definition().name(), objectMapper.createObjectNode()),
                        call("call-second", second.definition().name(), objectMapper.createObjectNode()),
                        call("call-third", third.definition().name(), objectMapper.createObjectNode())
                ));

        AgentRunResult result = runtime(
                modelGateway,
                workspace,
                List.of(first, second, third, new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.PARTIAL, result.status());
        assertEquals(AgentTerminationReason.WORKSPACE_SYNC_FAILURE, result.terminationReason());
        assertEquals(3, result.toolCallCount());
        assertEquals(1, result.successfulToolCallCount());
        assertEquals(1, first.invocations);
        assertEquals(0, second.invocations);
        assertEquals(0, third.invocations);
    }

    @Test
    void shouldRefreshBeforeFinishAndRequireACorrectedGeneration() {
        DriftingWorkspace workspace = new DriftingWorkspace();
        FakeModelGateway modelGateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-stale-finish",
                        call("call-stale-finish", FinishTaskTool.NAME, finishArguments(0))
                ))
                .enqueueResponse(toolResponse(
                        "response-current-finish",
                        call("call-current-finish", FinishTaskTool.NAME, finishArguments(1))
                ));

        AgentRunResult result = runtime(
                modelGateway,
                workspace,
                List.of(new FinishTaskTool(objectMapper))
        ).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(2, result.toolCallCount());
        assertEquals(1, workspace.generation);
        List<ModelMessage> correctionRequest = modelGateway.receivedRequests().get(1).messages();
        assertTrue(correctionRequest.stream()
                .filter(message -> message.role() == ModelRole.USER)
                .map(ModelMessage::content)
                .anyMatch(content -> content.contains("finish using generation 1")));
        assertTrue(correctionRequest.stream()
                .filter(message -> message.role() == ModelRole.USER)
                .map(ModelMessage::content)
                .anyMatch(content -> content.contains("authoritative at generation 1")));
    }

    private AgentRuntime runtime(
            FakeModelGateway modelGateway,
            WorkspaceGateway workspace,
            List<AgentTool> tools
    ) {
        AgentRuntimePolicy policy = new AgentRuntimePolicy(
                "fake-model",
                5,
                6,
                1,
                1,
                1024,
                0.0
        );
        ToolRegistry registry = new ToolRegistry(tools);
        executionConfig = AgentTestExecutionConfigs.forTools(tools, policy);
        return new AgentRuntime(
                modelGateway,
                promptAssembler(),
                new MessageFactory(objectMapper),
                registry,
                workspace,
                new CompletionInspector(objectMapper, workspace),
                AgentTestExecutionConfigs.resolver(registry)
        );
    }

    private PromptAssembler promptAssembler() {
        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "workspace-drift-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return "Use the latest authoritative Workspace state.";
            }
        };
        return new PromptAssembler(List.of(section));
    }

    private AgentExecutionContext context() {
        AgentRunContext run = new AgentRunContext(
                "run-drift",
                42L,
                "7/42",
                SnapshotScope.of("a".repeat(40))
        );
        return new AgentExecutionContext(
                "session-drift",
                run,
                9L,
                "Inspect the latest Workspace",
                new WorkspaceBinding(workspaceId),
                new WorkspaceExecutionPermit(run.runId(), workspaceId, 1L),
                executionConfig
        );
    }

    private ModelResponse toolResponse(String responseId, ToolCall... calls) {
        return new ModelResponse(
                responseId,
                null,
                List.of(calls),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );
    }

    private ToolCall call(String id, String name, ObjectNode arguments) {
        return new ToolCall(id, name, arguments);
    }

    private ObjectNode finishArguments(long generation) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", generation);
        arguments.put("summary", "Workspace inspected");
        arguments.putArray("findings");
        arguments.putArray("agentModifiedFiles");
        arguments.putArray("claimedValidations");
        arguments.putArray("risks");
        arguments.putArray("followUps");
        return arguments;
    }

    private final class RecordingReadTool implements AgentTool {
        private int invocations;

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            schema.put("additionalProperties", false);
            return new ToolDefinition("readContext", "Reads current context", schema);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, com.fasterxml.jackson.databind.JsonNode arguments) {
            invocations++;
            return ToolResult.success(
                    objectMapper.createObjectNode().put("generation", workspaceGeneration())
            );
        }

        private long workspaceGeneration() {
            return 1;
        }
    }

    private static final class DriftingWorkspace implements WorkspaceGateway {
        private int refreshCalls;
        private long generation;

        @Override
        public PatchBatchResult applyPatch(
                WorkspaceId workspaceId,
                WorkspaceExecutionPermit executionPermit,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
            refreshCalls++;
            if (refreshCalls == 2) {
                long before = generation;
                generation++;
                return new WorkspaceRefresh(before, generation, true);
            }
            return new WorkspaceRefresh(generation, generation, false);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return new WorkspaceDiff(generation, List.of(), 0, 0, 0, false, "");
        }
    }

    private static class BatchDriftingWorkspace implements WorkspaceGateway {
        private long generation;
        private boolean externalChangePending;

        void markExternalChange() {
            externalChangePending = true;
        }

        long currentGeneration() {
            return generation;
        }

        @Override
        public PatchBatchResult applyPatch(
                WorkspaceId workspaceId,
                WorkspaceExecutionPermit executionPermit,
                WorkspaceMutationCommand command
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
            long before = generation;
            if (externalChangePending) {
                externalChangePending = false;
                generation++;
            }
            return new WorkspaceRefresh(before, generation, generation > before);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return new WorkspaceDiff(generation, List.of(), 0, 0, 0, false, "");
        }
    }

    private static final class FailingBatchWorkspace extends BatchDriftingWorkspace {
        private final int failingRefreshCall;
        private int refreshCalls;

        private FailingBatchWorkspace(int failingRefreshCall) {
            this.failingRefreshCall = failingRefreshCall;
        }

        @Override
        public WorkspaceRefresh refreshWorkspace(WorkspaceId workspaceId) {
            refreshCalls++;
            if (refreshCalls == failingRefreshCall) {
                throw new WorkspaceOperationException(
                        WorkspaceOperationException.Reason.WORKSPACE_UNAVAILABLE,
                        "WORKSPACE_ROOT_UNAVAILABLE",
                        "Workspace disappeared between tool calls"
                );
            }
            return super.refreshWorkspace(workspaceId);
        }
    }

    private final class ObservingTool implements AgentTool {
        private final String name;
        private final BatchDriftingWorkspace workspace;
        private final Runnable afterExecution;
        private final List<Long> observedGenerations = new java.util.ArrayList<>();
        private int invocations;

        private ObservingTool(
                String name,
                BatchDriftingWorkspace workspace,
                Runnable afterExecution
        ) {
            this.name = name;
            this.workspace = workspace;
            this.afterExecution = afterExecution;
        }

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            schema.put("additionalProperties", false);
            return new ToolDefinition(name, "Observes the current Workspace", schema);
        }

        @Override
        public ToolResult execute(
                ToolExecutionContext context,
                com.fasterxml.jackson.databind.JsonNode arguments
        ) {
            invocations++;
            observedGenerations.add(workspace.currentGeneration());
            afterExecution.run();
            return ToolResult.success(
                    objectMapper.createObjectNode()
                            .put("generation", workspace.currentGeneration())
            );
        }
    }

    private final class ExpectedGenerationWriteTool implements AgentTool {
        private final BatchDriftingWorkspace workspace;
        private int invocations;
        private boolean mutated;

        private ExpectedGenerationWriteTool(BatchDriftingWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("expectedGeneration")
                    .put("type", "integer")
                    .put("minimum", 0);
            schema.putArray("required").add("expectedGeneration");
            schema.put("additionalProperties", false);
            return new ToolDefinition(
                    "writeAtExpectedGeneration",
                    "Writes only at the expected Workspace generation",
                    schema
            );
        }

        @Override
        public ToolResult execute(
                ToolExecutionContext context,
                com.fasterxml.jackson.databind.JsonNode arguments
        ) {
            invocations++;
            long expected = arguments.path("expectedGeneration").longValue();
            if (expected != workspace.currentGeneration()) {
                return ToolResult.error(
                        ToolStatus.CONFLICT,
                        objectMapper.createObjectNode()
                                .put("expectedGeneration", expected)
                                .put("currentGeneration", workspace.currentGeneration()),
                        "STALE_WORKSPACE_GENERATION",
                        "Expected generation does not match the current Workspace",
                        false
                );
            }
            mutated = true;
            return ToolResult.success(objectMapper.createObjectNode());
        }
    }
}
