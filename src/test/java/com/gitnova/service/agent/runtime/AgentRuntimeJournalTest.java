package com.gitnova.service.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.dto.ToolCall;
import com.gitnova.service.agent.AgentTestExecutionConfigs;
import com.gitnova.service.agent.completion.*;
import com.gitnova.service.agent.execution.AgentExecutionControl;
import com.gitnova.service.agent.journal.*;
import com.gitnova.service.agent.model.*;
import com.gitnova.service.agent.persistence.AgentEventAppender;
import com.gitnova.service.agent.persistence.CanonicalJsonCodec;
import com.gitnova.service.agent.prompt.AssembledPrompt;
import com.gitnova.service.agent.prompt.PromptAssembler;
import com.gitnova.service.agent.tool.ToolRegistry;
import com.gitnova.service.agent.tool.ToolResult;
import com.gitnova.service.agent.tool.ToolSetResolver;
import com.gitnova.service.agent.tools.FinishTaskTool;
import com.gitnova.service.agent.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeJournalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModelGateway model = mock(ModelGateway.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final RunJournal journal = mock(RunJournal.class);
    private final CompletionInspector inspector = mock(CompletionInspector.class);
    private final List<String> actions = new ArrayList<>();
    private final List<ModelCallStartedPayload> intents = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(2);
    private final RunJournalScope scope = new RunJournalScope("session", "task", "run", "worker", 1, "a".repeat(64));
    private AgentRuntime runtime;
    private AgentExecutionContext context;
    private String failAt;

    @BeforeEach
    void setUp() {
        WorkspaceGateway workspace = mock(WorkspaceGateway.class);
        when(workspace.refreshWorkspace(any())).thenReturn(new WorkspaceGateway.WorkspaceRefresh(0, 0, false));
        PromptAssembler prompt = mock(PromptAssembler.class);
        when(prompt.assemble(any())).thenReturn(new AssembledPrompt("policy", "Use tools then finish."));
        ToolSetResolver resolver = mock(ToolSetResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(List.of(new FinishTaskTool(mapper).definition()));
        when(tools.isTerminal("finishTask")).thenReturn(true);
        when(tools.execute(any(), anyString(), any())).thenAnswer(call -> {
            actions.add("execute:" + call.getArgument(1));
            return ToolResult.success(mapper.createObjectNode().put("generation", 0));
        });
        var draft = new AgentCompletionDraft(0, "Observed repository state", List.of(), List.of(), List.of(), List.of(), List.of());
        var outcome = new AgentCompletionOutcome(CompletionDisposition.NO_CHANGES, draft,
                new WorkspaceGateway.WorkspaceDiff(0, List.of(), 0, 0, 0, false, ""), null);
        when(inspector.inspect(any(), any(), any())).thenReturn(CompletionDecision.accepted(outcome));
        when(journal.appendModelCallStarted(any(), any())).thenAnswer(call -> {
            intents.add(call.getArgument(1)); return commit("started");
        });
        when(journal.appendModelResponse(any(), any())).thenAnswer(call -> commit("response"));
        when(journal.appendToolResult(any(), any())).thenAnswer(call -> commit("result"));
        when(journal.appendHarnessFeedback(any(), any(), nullable(String.class))).thenAnswer(call -> commit("feedback"));
        when(journal.appendCompletionDecision(any(), any())).thenAnswer(call -> commit("decision"));
        when(model.complete(any())).thenAnswer(call -> {
            actions.add("model");
            long number = actions.stream().filter("model"::equals).count();
            return response(number == 1 ? "read" : "finish", number == 1 ? "readFile" : "finishTask");
        });
        runtime = new AgentRuntime(model, prompt, new MessageFactory(mapper), tools, workspace,
                inspector, resolver, journal, new CanonicalJsonCodec(mapper));
        WorkspaceId id = WorkspaceId.generate();
        context = new AgentExecutionContext("session", new AgentRunContext("run", 1L, "1/1",
                SnapshotScope.of("a".repeat(40))), 1L, "Inspect repository", new WorkspaceBinding(id),
                new WorkspaceExecutionPermit("run", id, 1), AgentTestExecutionConfigs.minimal());
    }

    @Test
    void commitsEveryBoundaryBeforeProceedingAndPersistsAcceptedOutcome() {
        var result = run();
        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(List.of("started", "model", "response", "execute:readFile", "result",
                "started", "model", "response", "execute:finishTask", "result", "decision"), actions);
        assertEquals(2, intents.get(0).contextThroughRunStepSequence());
        assertEquals(5, intents.get(1).contextThroughRunStepSequence());
        assertTrue(intents.get(0).requestDigest().matches("[a-f0-9]{64}"));
        verify(journal).appendCompletionDecision(eq(scope), argThat(payload ->
                payload.decision().outcome().draft().summary().equals("Observed repository state")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"started", "response", "result", "decision"})
    void stopsImmediatelyWhenJournalCannotCommit(String boundary) {
        failAt = boundary;
        assertThrows(IllegalStateException.class, this::run);
        assertEquals(boundary, actions.get(actions.size() - 1));
        if (boundary.equals("started")) verifyNoInteractions(model);
        if (boundary.equals("response")) verify(tools, never()).execute(any(), anyString(), any());
        if (boundary.equals("result")) verify(model, times(1)).complete(any());
    }

    @Test
    void feedbackMustCommitBeforeAnotherModelCall() {
        doReturn(new ModelResponse("text", "Done", List.of(),
                ModelUsage.unknown(), ModelFinishReason.STOP)).when(model).complete(any());
        failAt = "feedback";
        assertThrows(IllegalStateException.class, this::run);
        verify(model, times(1)).complete(any());
    }

    @Test
    void rejectedCompletionRecordsDecisionAndCausalFeedbackBeforeCorrection() {
        var accepted = inspector.inspect(context, new RunStateView(java.util.Optional.empty()),
                ToolResult.success(mapper.createObjectNode()));
        when(inspector.inspect(any(), any(), any())).thenReturn(CompletionDecision.correctable("Refresh evidence"), accepted);
        doReturn(response("finish-one", "finishTask"), response("finish-two", "finishTask"))
                .when(model).complete(any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(journal, times(2)).appendCompletionDecision(eq(scope), any());
        verify(journal).appendHarnessFeedback(eq(scope), argThat(payload ->
                payload.kind() == HarnessFeedbackKind.COMPLETION_CORRECTION),
                eq("run:run:tool-call:finish-one:result"));
        assertEquals(List.of("started", "response", "execute:finishTask", "result", "decision", "feedback",
                "started", "response", "execute:finishTask", "result", "decision"), actions);
    }

    @Test
    void mixedTerminalRejectionsAreJournaledWithoutExecutingRejectedCalls() {
        var mixed = new ModelResponse("mixed", "", List.of(
                new ToolCall("mixed-read", "readFile", mapper.createObjectNode()),
                new ToolCall("mixed-finish", "finishTask", mapper.createObjectNode())),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
        doReturn(mixed, response("finish-alone", "finishTask")).when(model).complete(any());
        assertEquals(AgentRunStatus.COMPLETED, run().status());
        verify(tools, times(1)).execute(any(), eq("finishTask"), any());
        verify(tools, never()).execute(any(), eq("readFile"), any());
        verify(journal, times(3)).appendToolResult(eq(scope), any());
    }

    @Test
    void rejectsDuplicateToolIdentityBeforeExecutingAgain() {
        doReturn(response("repeated", "readFile")).when(model).complete(any());
        assertEquals(AgentTerminationReason.INVALID_MODEL_PROTOCOL, run().terminationReason());
        verify(tools, times(1)).execute(any(), anyString(), any());
    }

    @Test
    void productionCannotSilentlyUseStandaloneEntryPoint() {
        assertThrows(IllegalStateException.class, () -> runtime.run(context));
        verifyNoInteractions(model);
    }

    @Test
    void committedIntentIsNotAutomaticallyReplayedWithoutAProjector() {
        doReturn(new AgentEventAppender.AppendResult(3, 3, 3L, true))
                .when(journal).appendModelCallStarted(any(), any());
        assertThrows(IllegalStateException.class, this::run);
        verifyNoInteractions(model);
    }

    @Test
    void recoveryStopsWithoutCallingModelOrToolsUntilHistoryCanBeProjected() {
        when(journal.hasModelHistory(scope)).thenReturn(true);
        assertEquals(AgentTerminationReason.RECOVERY_CONTEXT_REQUIRED, run().terminationReason());
        verifyNoInteractions(model);
        verify(tools, never()).execute(any(), anyString(), any());
    }

    private AgentRunResult run() {
        return runtime.run(context, new AgentExecutionControl(), scope, 2);
    }

    private ModelResponse response(String id, String name) {
        return new ModelResponse("response-" + id, "", List.of(new ToolCall(id, name, mapper.createObjectNode())),
                ModelUsage.unknown(), ModelFinishReason.TOOL_CALLS);
    }

    private AgentEventAppender.AppendResult commit(String boundary) {
        actions.add(boundary);
        if (boundary.equals(failAt)) throw new IllegalStateException("simulated database failure");
        long seq = sequence.incrementAndGet();
        return new AgentEventAppender.AppendResult(seq, seq, seq, false);
    }
}
