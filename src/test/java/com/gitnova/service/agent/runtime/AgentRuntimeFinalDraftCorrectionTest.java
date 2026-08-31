package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.completion.CompletionInspector;
import com.gitnova.service.agent.model.FakeModelGateway;
import com.gitnova.service.agent.model.MessageFactory;
import com.gitnova.service.agent.model.ModelFinishReason;
import com.gitnova.service.agent.model.ModelMessage;
import com.gitnova.service.agent.model.ModelRequest;
import com.gitnova.service.agent.model.ModelResponse;
import com.gitnova.service.agent.model.ModelRole;
import com.gitnova.service.agent.model.ModelUsage;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.prompt.PromptSection;
import com.gitnova.service.agent.tool.AgentTool;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.PatchBatchResult;
import com.gitnova.service.agent.workspace.SnapshotScope;
import com.gitnova.service.agent.workspace.WorkspaceBinding;
import com.gitnova.service.agent.workspace.WorkspaceExecutionPermit;
import com.gitnova.service.agent.workspace.WorkspaceGateway;
import com.gitnova.service.agent.workspace.WorkspaceId;
import com.gitnova.service.agent.workspace.WorkspaceMutationCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeFinalDraftCorrectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnInvalidDraftObservationAndCompleteAfterModelCorrection() throws Exception {
        ObjectNode invalid = validFinish(0);
        invalid.put("summary", "");
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse("response-invalid", finishCall("call-invalid", invalid)))
                .enqueueResponse(toolResponse("response-valid", finishCall("call-valid", validFinish(0))));

        AgentRunResult result = runtime(gateway, 1, 0).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(2, result.modelCallCount());
        assertEquals(2, result.toolCallCount());
        ModelRequest correctionRequest = gateway.receivedRequests().get(1);
        List<ModelMessage> messages = correctionRequest.messages();
        ModelMessage toolRejection = messages.get(messages.size() - 2);
        ModelMessage feedback = messages.get(messages.size() - 1);
        assertEquals(ModelRole.TOOL, toolRejection.role());
        assertEquals("call-invalid", toolRejection.toolCallId());
        assertEquals(ModelRole.USER, feedback.role());
        assertTrue(feedback.content().contains("completion draft was rejected"));
        assertTrue(feedback.content().contains("finishTask alone"));
    }

    @Test
    void shouldCorrectStaleGenerationAndCompleteWithFreshGeneration() {
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse(
                        "response-stale",
                        finishCall("call-stale", validFinish(0))
                ))
                .enqueueResponse(toolResponse(
                        "response-fresh",
                        finishCall("call-fresh", validFinish(1))
                ));

        AgentRunResult result = runtime(gateway, 1, 1).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(1, result.completionOutcome().canonicalDiff().generation());
        ModelMessage feedback = gateway.receivedRequests().get(1).messages()
                .get(gateway.receivedRequests().get(1).messages().size() - 1);
        assertTrue(feedback.content().contains("generation 1"));
    }

    @Test
    void shouldTerminatePartialWhenCompletionCorrectionBudgetIsExhausted() {
        ObjectNode invalid = validFinish(0);
        invalid.put("summary", "");
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(toolResponse("response-invalid-1", finishCall("call-1", invalid)))
                .enqueueResponse(toolResponse("response-invalid-2", finishCall("call-2", invalid.deepCopy())));

        AgentRunResult result = runtime(gateway, 1, 0).run(context());

        assertEquals(AgentRunStatus.FAILED, result.status());
        assertEquals(AgentTerminationReason.INVALID_COMPLETION_DRAFT, result.terminationReason());
        assertNull(result.completionOutcome());
        assertEquals(0, result.successfulToolCallCount());
    }

    @Test
    void shouldRejectMixedTerminalCallsWithoutExecutingEitherTool() {
        ToolCall finish = finishCall("call-finish", validFinish(0));
        ToolCall other = new ToolCall(
                "call-other",
                "unknownTool",
                objectMapper.createObjectNode()
        );
        FakeModelGateway gateway = new FakeModelGateway()
                .enqueueResponse(new ModelResponse(
                        "response-mixed",
                        null,
                        List.of(other, finish),
                        ModelUsage.unknown(),
                        ModelFinishReason.TOOL_CALLS
                ))
                .enqueueResponse(toolResponse(
                        "response-corrected",
                        finishCall("call-corrected", validFinish(0))
                ));

        AgentRunResult result = runtime(gateway, 1, 0).run(context());

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(ProtocolDeviation.MIXED_TERMINAL_TOOL_CALLS, result.lastProtocolDeviation());
        assertEquals(3, result.toolCallCount());
        assertEquals(1, result.successfulToolCallCount());
    }

    private AgentRuntime runtime(
            FakeModelGateway gateway,
            int maxCorrections,
            long generation
    ) {
        WorkspaceGateway workspace = new EmptyWorkspace(generation);
        List<AgentTool> tools = List.of(new FinishTaskTool(objectMapper));
        return new AgentRuntime(
                gateway,
                promptAssembler(),
                new MessageFactory(objectMapper),
                new ToolRegistry(tools),
                workspace,
                new CompletionInspector(objectMapper, workspace),
                new AgentRuntimePolicy(
                        "fake-model",
                        5,
                        6,
                        1,
                        maxCorrections,
                        1024,
                        0.0
                )
        );
    }

    private PromptAssembler promptAssembler() {
        PromptSection section = new PromptSection() {
            @Override
            public String key() {
                return "correction-test";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public String render(AgentRunContext context) {
                return "Call finishTask alone when done.";
            }
        };
        return new PromptAssembler(List.of(section));
    }

    private AgentExecutionContext context() {
        AgentRunContext run = new AgentRunContext(
                "run-correction",
                42L,
                "7/42",
                SnapshotScope.of("a".repeat(40))
        );
        WorkspaceId workspaceId = WorkspaceId.generate();
        return new AgentExecutionContext(
                "session-1",
                run,
                7L,
                "Explain the current implementation",
                new WorkspaceBinding(workspaceId),
                new WorkspaceExecutionPermit(run.runId(), workspaceId, 1L),
                AgentCapabilityPolicy.cloudAgent()
        );
    }

    private ModelResponse toolResponse(String id, ToolCall call) {
        return new ModelResponse(
                id,
                null,
                List.of(call),
                ModelUsage.unknown(),
                ModelFinishReason.TOOL_CALLS
        );
    }

    private ToolCall finishCall(String id, ObjectNode arguments) {
        return new ToolCall(id, FinishTaskTool.NAME, arguments);
    }

    private ObjectNode validFinish(long generation) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("expectedGeneration", generation);
        arguments.put("summary", "Task completed");
        arguments.putArray("findings");
        arguments.putArray("agentModifiedFiles");
        arguments.putArray("claimedValidations");
        arguments.putArray("risks");
        arguments.putArray("followUps");
        return arguments;
    }

    private static final class EmptyWorkspace implements WorkspaceGateway {
        private final long generation;

        private EmptyWorkspace(long generation) {
            this.generation = generation;
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
            return new WorkspaceRefresh(generation, generation, false);
        }

        @Override
        public WorkspaceDiff getWorkspaceDiff(WorkspaceId workspaceId) {
            return new WorkspaceDiff(generation, List.of(), 0, 0, 0, false, "");
        }
    }
}
